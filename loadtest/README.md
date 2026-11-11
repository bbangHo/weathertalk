# 백엔드 부하테스트 (k6)

날씨톡톡 백엔드의 핵심 API에 출시 후 예상 트래픽을 걸어 지연·오류율·DB/Redis 사용량을 본다.

| 파일 | 하는 일 |
|---|---|
| `weathertalk.js` | k6 스크립트. 시나리오 4개(smoke·load·stress·spike), 테스트 회원 준비, 합격 기준 |
| `loadtest.env` | 부하테스트용 백엔드 환경 변수. **외부 API 차단**·쓰기 제한 완화·포트·지표 공개. 비밀값 없음 |
| `watch.sh` | 부하 중 5초마다 Hikari 풀·JVM·Postgres 연결·Redis ops·컨테이너 CPU/메모리를 찍는다 |
| `results/` | 결과 JSON·로그 (git 제외) |

k6는 설치하지 않고 도커 이미지 `grafana/k6:2.3.0`으로 돌린다.

## 1. 대상 API와 트래픽 가정

### 대상 API (요청 비율)

| 종류 | 요청 | 비율 |
|---|---|---|
| 홈 | `GET /api/v1/home?neighborhoodId=` (회원 60% · 비회원 40%) | 35% |
| 커뮤니티 피드 | `GET /api/v1/community/neighborhoods/{id}/posts` | 20% |
| 동네 체감 | `GET /api/v1/feeling/neighborhoods/{id}` | 10% |
| 글 상세 | `GET /api/v1/community/posts/{postId}` | 8% |
| 현장 제보 현황 | `GET /api/v1/weather/neighborhoods/{id}/field-reports` | 8% |
| 동네 검색 | `GET /api/v1/neighborhoods/search?q=` | 7% |
| 체감 기록 | `POST /api/v1/feeling/records` | 6% |
| 좋아요 | `PUT /api/v1/community/posts/{id}/like` | 3% |
| 글쓰기 | `POST /api/v1/community/posts` (사진 없음) | 1.5% |
| 현장 제보 | `POST /api/v1/weather/field-reports` | 1.5% |

**읽기 : 쓰기 = 88 : 12.** 근거: 사업계획 KPI의 체감 기록 전환율 목표 15%(방문자 중 기록한 비율). 한 세션이 요청 6~8개를 보내고 그중 기록이 1개 안팎이면 쓰기는 요청의 10% 남짓이다. 글쓰기·제보는 기록보다 훨씬 드물다.

요청은 고정 좌표 5곳(역삼·반포·서교·잠실·수원)의 동네로 몰린다. 출시 초기 '씨앗 동네'에 사용자가 몰리는 모습을 흉내 낸 것이고, 같은 동네의 글·체감 행에 쓰기가 몰려 잠금 경합도 드러난다.

### 사용자 수와 피크 RPS

`docs/business/business-plan.md` 11절 KPI: 3개월 기록자 50명, **6개월 '계속' 기준 MAU 3,000명**. 성능 목표는 그 **10배(MAU 3만)** 로 여유를 둔다.

| 항목 | 가정 | 값 |
|---|---|---|
| MAU | 6개월 목표 × 10 | 30,000 |
| DAU | 날씨 앱은 매일 쓰는 앱 → DAU/MAU 30% | 9,000 |
| 아침 피크 1시간(7~8시) | 하루 세션의 30% | 2,700 세션 |
| 세션당 요청 | 홈·피드·체감 등 | 8개 |
| 피크 시간 평균 | 2,700 × 8 / 3,600 | 6 RPS |
| 피크 분 (평균의 3배) | 출근 직전 몰림 | **약 20 RPS → load 목표** |
| 알림 직후 순간 피크 | 07:00 날씨 알림 뒤 1분 안에 DAU의 10%가 3요청 | 900 × 3 / 60 = 45 RPS → **spike 60 RPS** |

`PEAK_RPS`(기본 20)로 바꿀 수 있다. spike·stress는 이 값의 배수로 움직인다.

## 2. 시나리오

| 이름 | 모양 | 걸리는 시간 | 보는 것 |
|---|---|---|---|
| `smoke` | 1명, 10가지 요청을 순서대로 2번씩(20회) | 몇 초 | 스크립트·서버가 동작하는지 |
| `load` | 2분 동안 20 RPS까지 올려 10분 유지 | 13분 | 예상 피크에서 합격 기준을 지키는지 |
| `stress` | 20 → 50 → 100 → 150 → 200 RPS, 단계마다 3분 유지 | 21분 | 어디서 무너지는지(오류율 1% 넘으면 30초 뒤 스스로 멈춤) |
| `spike` | 5 RPS → 10초 만에 60 RPS, 2분 유지 → 5 RPS로 2분 | 5분 | 알림 직후 몰림을 견디고 원래대로 돌아오는지 |

요청 하나 = k6 반복 하나라서 도착률(RPS)을 직접 정한다(`ramping-arrival-rate`). 서버가 느려지면 k6가 VU를 늘려 같은 RPS를 유지하려 하고, `maxVUs`에 닿으면 `dropped_iterations`가 쌓인다 — 그 자체가 처리 한계 신호다.

## 3. 인증: 테스트용 토큰

- **개발 전용 로그인** `POST /api/v1/dev/login` 을 쓴다. `local`·`staging` 프로파일에만 등록되고 운영(`prod`)에는 없다(`@DevelopmentOnly`).
- k6 `setup()`이 `loadtest-1` ~ `loadtest-N` 회원을 만든다(처음이면 약관 전부 동의 후 가입). ID가 고정이라 다시 돌리면 로그인만 한다.
- 접근 토큰 수명은 1시간(`member.access-token-ttl`)이라 모든 시나리오가 한 토큰으로 끝난다.
- 비밀값이 필요 없다. 토큰은 실행할 때 메모리에서만 쓰고 파일로 남기지 않는다. 운영 소셜 로그인 키·mTLS 인증서는 쓰지 않는다.
- `setup()`은 동네 5곳 판정, 상세·좋아요용 글 5개 작성도 한다. 내 글에는 좋아요할 수 없어(`SELF_LIKE_NOT_ALLOWED`) 좋아요는 작성자가 아닌 회원이 누른다.

## 4. 외부 API 보호 (필수)

부하가 기상청·에어코리아·FCM·소셜 로그인으로 새지 않게 `loadtest.env`가 두 겹으로 막는다.

1. **키를 비운다** — `WEATHER_KMA_SERVICE_KEY=`, `WEATHER_AIRKOREA_SERVICE_KEY=`. 키가 없으면 수집 코드가 호출 전에 멈춘다(`KmaHttpSource` → `KeyMissing`, 로그 "수집 생략: 기상청 인증키가 설정되지 않아"). OS 환경 변수가 `.env`보다 이기므로 루트 `.env`에 실제 키가 있어도 빈 값이 우선한다.
2. **주소를 막는다** — `WEATHER_SOURCE_KMA_BASEURL`·`WEATHER_SOURCE_AIRKOREA_BASEURL`을 `http://127.0.0.1:9`(닫힌 포트)로. 키가 실수로 들어가도 연결 거부로 끝난다.
3. FCM 자격 파일 경로를 비워 푸시 발송을 끈다. 소셜 로그인은 개발 로그인만 쓰므로 카카오·애플·구글·토스로 나가지 않는다.

**한계:** 이 모드(A)에서는 날씨 데이터가 없어 홈의 날씨 섹션이 `EMPTY`로 빨리 끝난다. 실제보다 홈이 **가볍게** 측정된다. 날씨가 채워진 상태를 재려면 아래 B 모드가 필요하다(실행 전 결정 사항).

| 모드 | 방법 | 외부 호출 | 상태 |
|---|---|---|---|
| A. 키 없음 (기본) | `loadtest.env` 그대로 | 0건 | 준비됨, smoke 확인 |
| B. 캐시 예열 | 실제 키로 띄워 테스트 동네 5곳의 홈을 한 번씩 불러 수집 → 키를 비우고 다시 띄워 부하 | 동네 5곳 × 수집 종류 몇 개 = 수십 건, 한 번만 | 결정 필요 |
| C. 모의 서버 | 기상청 응답 모양의 고정 JSON을 돌려주는 모의 서버를 `..._BASEURL`로 | 0건 | 미구현(테스트의 WireMock 픽스처 재사용 가능) |

B는 수집 데이터가 DB에 남으므로(단기예보 최대 6시간 유효) 두 번째 기동에서 키 없이도 홈이 채워진다. 부하 중 사전 갱신 작업이 돌아도 키가 없어 나가지 않는다.

## 5. 실행 방법

### 로컬 (이 PC, Git Bash)

```bash
# 1) 백엔드: compose.yaml(PostGIS·Redis·SeaweedFS)을 자동으로 띄우고 18080 포트로 실행
cd backend
set -a; . ../loadtest/loadtest.env; set +a
./gradlew bootRun --args='--spring.profiles.active=local'
```

```bash
# 2) (다른 터미널) 자원 기록
bash loadtest/watch.sh > loadtest/results/watch-smoke.log
```

```bash
# 3) (저장소 루트에서) k6
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/loadtest:/scripts" -e BASE_URL=http://host.docker.internal:18080 -e SCENARIO=smoke grafana/k6:2.3.0 run --summary-export /scripts/results/smoke.json /scripts/weathertalk.js
```

`SCENARIO`를 `load`·`stress`·`spike`로 바꾼다. 합격 기준을 어기면 k6가 종료 코드 99로 끝난다. 맥·리눅스는 `$(pwd -W)` 대신 `$(pwd)`, `MSYS_NO_PATHCONV=1`은 빼도 된다.

주의:
- compose 프로젝트 이름이 `backend`라 원래 체크아웃과 **같은 로컬 DB 컨테이너·볼륨을 쓴다**. 테스트 회원 `loadtest-*`, "부하테스트 글", 체감 기록이 쌓인다. 지우려면 `docker compose -f backend/compose.yaml down -v`(로컬 데이터 전부 삭제).
- 이 PC에서 k6와 서버를 같이 돌리면 서로 CPU를 뺏는다. load 이상은 결과를 '상대 비교'로만 본다.

### 미니 PC (bbangho-server) — 사용자 확인 후에만

미리보기 서버는 공개 데모 중이다. **확인 없이 부하를 주지 않는다.** 스크립트도 `BASE_URL`이 로컬이 아니면 `ALLOW_REMOTE=1` 없이는 시작하지 않는다.

권장 방식: 데모와 **별도의 compose 프로젝트**(다른 DB·Redis 볼륨, `local` 프로파일 + `loadtest.env`)를 미니 PC에 띄우고, k6는 **미니 PC 안에서** `localhost`로 쏜다(터널·Cloudflare를 거치지 않게). 데모 컨테이너와 CPU를 나눠 쓰므로 데모가 느려질 수 있어, 접속이 적은 시간대에 한다.

## 6. 측정 지표와 합격 기준

`weathertalk.js`의 `thresholds`와 같다. 기준은 load(20 RPS)에서 지켜야 한다.

| 지표 | 기준 | 근거 |
|---|---|---|
| 오류율 `http_req_failed` | < 1% | |
| check 성공률 | > 99% | 응답 2xx + 홈 섹션 실패(`"FAILED"`) 없음 |
| 홈 `kind:home` | p95 < 800ms, p99 < 2s, max < 5s | 사업계획 품질 KPI "2초 넘는 지연 0건", 섹션 제한 시간 2s(`home.section-timeout`) |
| 그 밖 조회 `kind:read` | p95 < 300ms, p99 < 1s | |
| 쓰기 `kind:write` | p95 < 500ms, p99 < 1.5s | |
| `dropped_iterations` | 0 | 목표 RPS를 못 따라가면 생긴다 |
| Hikari `pending` | 계속 0 | 0보다 크면 DB 연결 대기 |
| Postgres CPU | load에서 70% 미만 | `watch.sh` |
| Redis | ops/s, 메모리 추세(늘기만 하면 키 수명 문제) | `watch.sh` |

홈 섹션은 시간 초과·오류가 나도 HTTP 200 안에 `status: FAILED`로 담긴다. 그래서 오류율만 보면 놓친다 → check `home 섹션 실패 없음`으로 따로 센다.

stress·spike는 합격·불합격보다 **무너지는 RPS**와 **회복 시간**을 기록한다.

## 7. 병목 후보

| # | 후보 | 왜 의심하나 | 볼 지표 |
|---|---|---|---|
| 1 | **Hikari 풀 10개 vs 홈 병렬 섹션** | 홈 한 번에 섹션 약 12개를 가상 스레드로 동시에 돌린다(`HomeComposer`, `SectionRunner`). 섹션마다 DB 연결을 잡으면 홈 동시 1~2건으로 풀(기본 10)이 찬다 | `hikaricp.connections.pending`, 홈 `FAILED`(TIMEOUT) |
| 2 | 처음 보는 동네의 수집 대기 | 데이터가 없으면 요청 스레드가 수집 잠금을 잡고 최대 `weather.collect.wait-timeout`(3s)까지 기다린다. 미리보기에서 홈 섹션이 2초를 넘던 원인. A 모드에선 키가 없어 바로 끝나서 드러나지 않는다 | B 모드에서 홈 p99 |
| 3 | 첫 요청(JVM 예열) | smoke 첫 실행에서 홈이 6.5초까지 걸렸고, 두 번째부터 0.2~0.5초 | 시작 직후 1분은 따로 본다 |
| 4 | 인기 글·피드 쿼리 | 동네별 최근 24시간 좋아요 정렬(`community.popular`), 커서 피드. 글이 쌓일수록 인덱스 의존 | Postgres CPU, `pg_stat_statements`(켜야 함) |
| 5 | 좋아요 수 갱신 경합 | 씨앗 동네 글 5개에 좋아요가 몰려 같은 행을 갱신 | 쓰기 p99, 잠금 대기 |
| 6 | 동네 검색 | 3,564개 행에 `ILIKE '%q%'` 4개 조건(`NeighborhoodRepository`) — 일반 인덱스를 못 타 순차 검색. 행이 적어 지금은 괜찮을 가능성이 크다 | 검색 p95 |
| 7 | 멱등 키·수집 잠금의 Redis 왕복 | 쓰기마다 멱등 키, 수집마다 잠금. Lettuce 단일 연결 | `redis_ops`, 쓰기 p95 |
| 8 | 요청마다 남는 경고 로그 | A 모드에선 홈마다 "수집 생략" 경고가 3줄씩 찍힌다 | 디스크 I/O (B 모드에선 사라짐) |

## 8. smoke 결과 (2026-11-11, 로컬 A 모드)

이 PC(Windows, Docker Desktop), 백엔드 `bootRun` + k6 도커, 1명 20회.

| 항목 | 값 |
|---|---|
| 요청 | 34건(준비 14 + 본 요청 20), 실패 0 |
| check | 22/22 통과(10가지 요청 모두 2xx, 홈 섹션 실패 없음) |
| 홈 | 약 0.2~0.5s (첫 실행만 1.1~6.5s, JVM 예열) |
| 그 밖 조회 | p95 약 0.2s |
| 쓰기 | p95 약 0.25s |
| 외부 호출 | 0건 (로그에 "기상청 인증키가 설정되지 않아 수집하지 않습니다"만) |

1명 기준 숫자라 성능 판단에는 쓰지 않는다. 스크립트가 동작한다는 확인이다.
