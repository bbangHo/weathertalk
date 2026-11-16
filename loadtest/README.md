# 백엔드 부하테스트 (k6)

날씨톡톡 백엔드의 핵심 API에 출시 후 예상 트래픽을 걸어 지연·오류율·DB/Redis 사용량을 본다.

> **부하테스트는 모두 미니 PC(bbangho-server)에서 한다.** 개발 PC는 메모리가 모자라 서버·DB·k6를 함께 돌리면 Docker가 멈춘다(2026-11-16 확인). 개발 PC에서는 스크립트를 고치기만 한다.

| 파일 | 하는 일 |
|---|---|
| `weathertalk.js` | k6 스크립트. 시나리오 4개(smoke·load·stress·spike), 테스트 회원 준비, 합격 기준 |
| `loadtest.env` | 부하테스트용 백엔드 환경 변수. **외부 API를 모의 서버로 돌림**·쓰기 제한 완화·포트·지표 공개. 비밀값 없음 |
| `mock-weather.js` | 기상청·에어코리아 모의 서버(Node, 의존성 없음). 어느 격자·시각이든 응답을 만들어 준다 |
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

**동네 분포:** 요청의 40%(`HOT_SHARE`)는 인기 동네 5곳(역삼·반포·서교·잠실·수원), 60%는 **전국 3,564개 동네**에 고르게 퍼진다. MAU 3만이면 사용자가 전국에 흩어지므로 캐시가 잘 안 맞는 동네·처음 보는 격자(전국 1,625개)의 수집 경로를 함께 재고, 인기 동네에서는 같은 글·체감 행에 쓰기가 몰리는 잠금 경합을 잰다.

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
- `setup()`은 인기 동네 5곳 판정, 전국 동네 목록 조회(시도 → 시군구 → 동네, 약 270건), 상세·좋아요용 글 5개 작성도 한다. 내 글에는 좋아요할 수 없어(`SELF_LIKE_NOT_ALLOWED`) 좋아요는 작성자가 아닌 회원이 누른다.

## 4. 외부 API 보호 (필수)

부하가 기상청·에어코리아·FCM·소셜 로그인으로 새지 않게 한다. **실제 키는 부하테스트 서버에 넣지 않는다.**

| 모드 | 방법 | 외부 호출 | 상태 |
|---|---|---|---|
| **C. 모의 서버 (기본)** | `mock-weather.js`를 18090에 띄우고 `loadtest.env`가 기상청·에어코리아 주소를 거기로 돌린다. 키는 가짜 값 | 0건 | 준비됨, 서버와 함께는 아직 미검증 |
| A. 키 없음 | 키를 비우고 주소를 닫힌 포트로(`loadtest.env` 주석) | 0건 | 2026-11-11 smoke 확인. 날씨 섹션이 `EMPTY`라 홈이 가볍게 측정된다 |
| B. 실제 키 예열 | 실제 키로 테스트 동네만 수집해 두기 | 격자당 몇 건 | **쓰지 않는다.** 전국 분포면 격자 1,625개 × 3~5건으로 하루 한도(10,000건)에 가깝다 |

C 모드가 막는 방법:

1. **주소를 바꾼다** — `WEATHER_SOURCE_KMA_BASEURL=http://localhost:18090/kma`, `WEATHER_SOURCE_AIRKOREA_BASEURL=http://localhost:18090/air`. OS 환경 변수가 `.env`보다 이기므로 `.env`에 실제 키·주소가 있어도 이 값이 우선한다.
2. **키는 가짜 값**(`loadtest-fake-key`) — 수집 코드가 '키 있음'으로 보고 모의 서버를 부르게 할 뿐, 실제 키가 아니다.
3. FCM 자격 파일 경로를 비워 푸시 발송을 끈다. 소셜 로그인은 개발 로그인만 쓰므로 카카오·애플·구글·토스로 나가지 않는다.

모의 서버는 `GET /counts`로 받은 호출 수를 알려 준다. 부하 뒤 이 수가 백엔드가 기상청에 보냈을 호출 수다(사전 갱신 작업 포함). 실제 기상청처럼 느린 원천을 흉내 내려면 지연(ms)을 준다: `node loadtest/mock-weather.js 18090 800`.

## 5. 실행 방법 (미니 PC)

미리보기 서버가 공개 데모 중이라, **데모와 섞이지 않게** 띄우고 **데모가 한산한 시간**에 한다. 스크립트는 `BASE_URL`이 로컬이 아니면 `ALLOW_REMOTE=1` 없이 시작하지 않는다 — k6는 미니 PC 안에서 `localhost`로 쏜다(터널·Cloudflare를 거치지 않게).

### 실행 전 확인할 것

- [ ] 데모가 쓰는 compose 프로젝트 이름·포트 확인. 부하테스트는 **다른 프로젝트 이름**(`COMPOSE_PROJECT_NAME=weathertalk-loadtest`)으로 띄워 DB·Redis 볼륨을 나눈다. `backend/compose.yaml`의 SeaweedFS는 호스트 포트 8333이 고정이라 데모와 겹치면 포트를 바꾸거나 빼야 한다(사진 업로드는 시나리오에 없다).
- [ ] 포트 18080(API)·18081(지표)·18090(모의 서버)이 비어 있는지.
- [ ] 미니 PC의 CPU·메모리 여유. 데모·부하 서버·k6가 같은 기계를 나눠 쓰므로 결과는 그 조건의 값이다.
- [ ] 이 브랜치(`loadtest/`)가 미니 PC에 있는지.

### 순서

```bash
# 1) 모의 날씨 서버 (터미널 1)
node loadtest/mock-weather.js
```

```bash
# 2) 백엔드 (터미널 2): compose.yaml(PostGIS·Redis)을 자동으로 띄우고 18080 포트로 실행
cd backend
export COMPOSE_PROJECT_NAME=weathertalk-loadtest
set -a; . ../loadtest/loadtest.env; set +a
./gradlew bootRun --args='--spring.profiles.active=local'
```

```bash
# 3) 자원 기록 (터미널 3)
mkdir -p loadtest/results
bash loadtest/watch.sh http://localhost:18081 weathertalk-loadtest > loadtest/results/watch-smoke.log
```

```bash
# 4) k6 (저장소 루트, 터미널 4) — smoke 먼저, 통과하면 load·spike·stress
docker run --rm --network host -v "$(pwd)/loadtest:/scripts" -e SCENARIO=smoke grafana/k6:2.3.0 run --summary-export /scripts/results/smoke.json /scripts/weathertalk.js
```

```bash
# 5) 모의 서버가 받은 호출 수
curl -s localhost:18090/counts
```

`SCENARIO`를 `load`·`spike`·`stress` 순서로 바꾼다. 합격 기준을 어기면 k6가 종료 코드 99로 끝난다.

뒷정리: 테스트 회원 `loadtest-*`, "부하테스트 글", 체감 기록이 부하테스트 DB에 쌓인다. 끝나면 `docker compose -p weathertalk-loadtest -f backend/compose.yaml down -v`로 부하테스트 프로젝트만 지운다(**데모 프로젝트 이름으로 실행하지 않는다**).

확인 필요: Spring Boot의 Docker Compose 지원이 `COMPOSE_PROJECT_NAME`을 따르는지 처음 실행 때 `docker ps`로 컨테이너 이름을 본다. 안 따르면 compose를 먼저 직접 띄우고 `spring.docker.compose.lifecycle-management=none` + DB·Redis 주소를 환경 변수로 넣는다.

## 6. 측정 지표와 합격 기준

`weathertalk.js`의 `thresholds`와 같다. 기준은 load(20 RPS)에서 지켜야 한다.

| 지표 | 기준 | 근거 |
|---|---|---|
| 오류율 `http_req_failed` | < 1% | |
| check 성공률 | > 99% | 응답 2xx + 홈 섹션 실패(`"FAILED"`) 없음 + 홈 현재 날씨 `OK`(모의 서버 연결 확인) |
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
| 2 | 처음 보는 동네의 수집 대기 | 데이터가 없으면 요청 스레드가 수집 잠금을 잡고 최대 `weather.collect.wait-timeout`(3s)까지 기다린다. 미리보기에서 홈 섹션이 2초를 넘던 원인. 전국 분포라 처음 보는 격자가 계속 나온다 | 홈 p99, 모의 서버에 지연(예: 800ms)을 줬을 때와 비교 |
| 3 | 첫 요청(JVM 예열) | smoke 첫 실행에서 홈이 6.5초까지 걸렸고, 두 번째부터 0.2~0.5초 | 시작 직후 1분은 따로 본다 |
| 4 | 인기 글·피드 쿼리 | 동네별 최근 24시간 좋아요 정렬(`community.popular`), 커서 피드. 글이 쌓일수록 인덱스 의존 | Postgres CPU, `pg_stat_statements`(켜야 함) |
| 5 | 좋아요 수 갱신 경합 | 인기 동네 글 5개에 좋아요가 몰려 같은 행을 갱신 | 쓰기 p99, 잠금 대기 |
| 6 | 동네 검색 | 3,564개 행에 `ILIKE '%q%'` 4개 조건(`NeighborhoodRepository`) — 일반 인덱스를 못 타 순차 검색. 행이 적어 지금은 괜찮을 가능성이 크다 | 검색 p95 |
| 7 | 멱등 키·수집 잠금의 Redis 왕복 | 쓰기마다 멱등 키, 수집마다 잠금. Lettuce 단일 연결 | `redis_ops`, 쓰기 p95 |
| 8 | 날씨 테이블 증가 | 전국 격자마다 단기예보 72칸 × 8범주가 쌓인다. 수집·조회 쿼리가 커진다 | Postgres CPU, 테이블 크기 |

## 8. 지금까지 결과

### smoke (2026-11-11, 개발 PC, A 모드, 인기 동네 5곳만)

미니 PC 원칙을 정하기 전, 스크립트 동작 확인용으로 한 번 돌렸다. 1명 20회.

| 항목 | 값 |
|---|---|
| 요청 | 34건(준비 14 + 본 요청 20), 실패 0 |
| check | 22/22 통과(10가지 요청 모두 2xx, 홈 섹션 실패 없음) |
| 홈 | 약 0.2~0.5s (첫 실행만 1.1~6.5s, JVM 예열) |
| 그 밖 조회 | p95 약 0.2s |
| 쓰기 | p95 약 0.25s |
| 외부 호출 | 0건 (로그에 "기상청 인증키가 설정되지 않아 수집하지 않습니다"만) |

1명 기준 숫자라 성능 판단에는 쓰지 않는다. 스크립트가 동작한다는 확인이다.

### C 모드 (모의 서버 + 전국 분포)

아직 실행하지 않았다. 모의 서버 단독 응답만 확인했다. 미니 PC에서 smoke부터 돌린다.
