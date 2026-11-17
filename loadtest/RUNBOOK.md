# 밤샘 부하테스트 실행 지시서 (미니 PC)

> 이 문서는 **사람이 자는 동안 에이전트(Claude 세션)가 혼자 따라 하는 순서**다. 중간에 사람에게 묻지 않는다. 막히면 그 단계를 건너뛰고 이유를 보고서에 적는다.
> 배경·합격 기준의 근거는 `loadtest/README.md`, 시나리오 설계는 로컬 계획서 `docs/plan/load-test-plan.md`.

## 0. 지켜야 할 것 (어기면 즉시 멈춘다)

1. **미니 PC(bbangho-server) 안에서만** 실행한다. k6는 `localhost:18080`에만 쏜다. `ALLOW_REMOTE=1`을 쓰지 않는다.
2. **데모를 건드리지 않는다.** 데모 컨테이너를 멈추거나 재시작하거나 지우지 않는다. `docker compose ... down`은 **`-p weathertalk-loadtest`로만** 한다.
3. **외부 API를 부르지 않는다.** 기상청·에어코리아는 모의 서버(`mock-weather.js`)로만. 실제 키를 `loadtest.env`나 셸에 넣지 않는다. `.env`의 실제 키는 `loadtest.env`가 덮어쓴다.
4. **데모가 느려지면 멈춘다.** 3절의 데모 감시가 경고를 내면 돌던 k6를 Ctrl+C로 끊고, 그 시나리오는 '데모 영향으로 중단'으로 기록한 뒤 다음 단계로 가지 않고 9절(정리)로 간다.
5. 코드·설정 파일을 고치지 않는다. 커밋·푸시하지 않는다. 결과는 `loadtest/results/`에만 쓴다.

## 1. 실행 목록

| 순서 | ID | 시나리오 | 명령의 SCENARIO / 옵션 | 시간 | 회차 | 다음으로 가는 조건 |
|---|---|---|---|---|---|---|
| 1 | LT-01 | smoke | `smoke` | 1분 | 1 | 종료 코드 0 + check 100%. 실패면 **전체 중단** |
| 2 | LT-02 | load 20 TPS | `load` | 13분 | 2 | 기록만 하고 계속 (합격 여부는 보고서에) |
| 3 | LT-03 | 느린 기상청 load | `load` + 모의 서버 지연 800ms, **DB 초기화 후** | 13분 | 2 | 계속 |
| 4 | LT-04 | spike 60 TPS | `spike` | 5분 20초 | 2 | 계속 |
| 5 | LT-05 | stress ~200 TPS | `stress`, **DB 초기화 후** | 최대 21분 | 1 | 마지막 |

- 회차 사이·시나리오 사이에 **2분** 쉰다.
- 같은 시나리오 2회의 홈 p95 차이가 10%를 넘으면 그 시나리오를 **1회 더** 돌린다.
- LT-06 soak(1시간)는 이번 밤에 하지 않는다.
- 예상 총 소요: 약 2시간 30분 (기동·초기화 포함).

## 2. 준비 (한 번)

```bash
cd ~/weatherR                                   # 이 브랜치가 체크아웃된 저장소 (경로가 다르면 맞춘다)
git log --oneline -1 -- loadtest                # loadtest/ 최신 커밋이 있는지
mkdir -p loadtest/results
RUN=$(date +%Y%m%d-%H%M); echo $RUN             # 이번 실행 이름. 결과 파일 앞에 붙인다
```

확인하고 `loadtest/results/$RUN-env.txt`에 적는다:

```bash
{ date; uname -a; nproc; free -h; df -h .; docker ps --format '{{.Names}} {{.Ports}}'; } > loadtest/results/$RUN-env.txt
```

- 포트 18080·18081·18090·8333이 비어 있어야 한다 (`ss -ltn | grep -E ':(18080|18081|18090|8333) '`). 8333을 데모가 쓰고 있으면: **SeaweedFS 없이** 띄운다 — 4절 '대안'.
- 여유 메모리가 3GB 미만이면 실행하지 않고 보고서에 '자원 부족'으로 적고 끝낸다.

## 3. 데모 감시 (k6를 돌리는 동안 계속)

데모 백엔드 주소를 `docker ps`에서 찾는다(데모 백엔드 컨테이너의 호스트 포트, 예: `localhost:8080`). 그 주소를 `DEMO`에 넣고 터미널 하나에서 돈다:

```bash
DEMO=http://localhost:<데모 포트>
while true; do
  t=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' "$DEMO/api/v1/neighborhoods/search?q=%EC%97%AD%EC%82%BC")
  echo "$(date +%T) $t" | tee -a loadtest/results/$RUN-demo.log
  case "$t" in 200\ [0-2].*) ;; *) echo "!!! 데모 경고: $t" | tee -a loadtest/results/$RUN-demo.log ;; esac
  sleep 60
done
```

`!!! 데모 경고`(응답 코드가 200이 아니거나 3초 이상)가 **2번 연속** 나오면 0절 4번대로 멈춘다. 데모 주소를 못 찾으면 감시 없이 진행하지 말고, 보고서에 이유를 적고 끝낸다.

## 4. 기동

터미널 A — 모의 날씨 서버:

```bash
node loadtest/mock-weather.js            # LT-03 때만: node loadtest/mock-weather.js 18090 800
```

터미널 B — 부하테스트 백엔드:

```bash
cd backend
export COMPOSE_PROJECT_NAME=weathertalk-loadtest
set -a; . ../loadtest/loadtest.env; set +a
./gradlew bootRun --args='--spring.profiles.active=local' 2>&1 | tee ../loadtest/results/$RUN-backend.log
```

기동 확인 (최대 10분 기다린다):

```bash
until curl -sf localhost:18081/actuator/health >/dev/null; do sleep 10; done
docker ps --format '{{.Names}}' | grep weathertalk-loadtest      # 컨테이너 이름 확인
```

- 컨테이너 이름이 `weathertalk-loadtest-postgres-1`처럼 나오지 않고 데모와 같은 이름이면 **즉시 백엔드를 끄고 중단**한다(데모 DB를 같이 쓰게 된다). 보고서에 적는다.
- **대안(8333 충돌 또는 프로젝트 이름이 안 먹을 때):** `docker compose -p weathertalk-loadtest -f backend/compose.yaml up -d postgres redis`로 먼저 띄우고, 할당된 포트를 `docker compose -p weathertalk-loadtest port postgres 5432`·`port redis 6379`로 확인해 `SPRING_DOCKER_COMPOSE_ENABLED=false SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:<포트>/weathertalk SPRING_DATASOURCE_USERNAME=weathertalk SPRING_DATASOURCE_PASSWORD=weathertalk SPRING_DATA_REDIS_URL=redis://localhost:<포트>`를 붙여 기동한다. 사진 업로드는 시나리오에 없어 SeaweedFS가 없어도 된다.

## 5. 시나리오 한 회차 실행

터미널 C — 자원 기록 (회차마다 새로 시작, 끝나면 Ctrl+C):

```bash
bash loadtest/watch.sh http://localhost:18081 weathertalk-loadtest > loadtest/results/$RUN-<ID>-<회차>-watch.log
```

터미널 D — k6:

```bash
docker run --rm --network host -v "$(pwd)/loadtest:/scripts" -e SCENARIO=<시나리오> grafana/k6:2.3.0 run \
  --summary-export /scripts/results/$RUN-<ID>-<회차>.json /scripts/weathertalk.js 2>&1 | tee loadtest/results/$RUN-<ID>-<회차>.txt
echo "exit=${PIPESTATUS[0]}" >> loadtest/results/$RUN-<ID>-<회차>.txt
curl -s localhost:18090/counts > loadtest/results/$RUN-<ID>-<회차>-mock.json
```

- 종료 코드 **99**는 합격 기준 미달이다. 실패가 아니라 결과다 — 기록하고 다음으로 간다(LT-01만 예외).
- k6가 `setup()`에서 멈추면(동네 목록·수도권 몫·로그인 실패) 그 메시지를 보고서에 적고 **전체 중단**.

## 6. DB 초기화 (LT-03, LT-05 전)

```bash
# 터미널 B의 백엔드를 Ctrl+C로 끈 뒤
docker compose -p weathertalk-loadtest -f backend/compose.yaml down -v
```

그다음 4절 기동을 다시 한다. LT-03은 터미널 A의 모의 서버도 끄고 **지연 800ms**로 다시 띄운다. LT-03이 끝나면 모의 서버를 **지연 없이** 다시 띄운 뒤 LT-04로 간다.

## 7. 순서 요약

```
2 준비 → 3 데모 감시 시작 → 4 기동
→ LT-01 ×1 (실패면 9로)
→ LT-02 ×2
→ 6 초기화 + 모의 서버 800ms → LT-03 ×2 → 모의 서버 0ms 재기동
→ LT-04 ×2
→ 6 초기화 → LT-05 ×1
→ 9 정리 → 8 보고서
```

## 8. 보고서

`loadtest/results/$RUN-report.md`에 쓴다. 숫자는 k6 JSON·watch 로그에서 그대로 옮긴다(추정하지 않는다).

1. **한 줄 결론**: LT-02 합격 여부, 한계 TPS(LT-05), 먼저 무너진 자원.
2. **표** (시나리오·회차마다 한 줄):

   | 시나리오 | 회차 | 실제 TPS | DB TPS(평균) | 오류율 | 홈 p95 / p99 | 조회 p95 | 쓰기 p95 | Hikari pending 최대 | Postgres CPU 최대 | dropped | 판정 |
   |---|---|---|---|---|---|---|---|---|---|---|---|

   - 실제 TPS = JSON의 `http_reqs.rate`. DB TPS = watch 로그 `db_tps` 평균(부하 유지 구간만). 홈 = `http_req_duration{kind:home}`.
   - 판정: 합격 / 불합격(어긴 기준 이름) / 중단(이유).
3. **LT-03**: 홈 p99, 홈 섹션 실패 check 비율, 모의 서버 호출 수.
4. **LT-04**: 몰림 뒤 p95가 몰림 전 수준으로 돌아오기까지 걸린 시간(watch·k6 로그로 대략).
5. **LT-05**: 오류율 1% 또는 홈 p99 2초를 처음 넘은 단계(TPS)와 그때 Hikari pending·Postgres CPU·JVM 스레드.
6. **데모 영향**: `$RUN-demo.log`의 경고 수.
7. **건너뛰거나 중단한 단계와 이유.**
8. **이상한 점**: 백엔드 로그의 ERROR·WARN 상위 5종(`grep -E 'ERROR|WARN' $RUN-backend.log | cut -c60-200 | sort | uniq -c | sort -rn | head`).

## 9. 정리 (항상, 중단했을 때도)

```bash
# k6·watch·데모 감시·모의 서버·백엔드를 모두 Ctrl+C로 끈다
docker compose -p weathertalk-loadtest -f backend/compose.yaml down -v
docker ps --format '{{.Names}}'          # 데모 컨테이너가 그대로 떠 있는지 확인해 보고서에 적는다
```

결과 파일(`loadtest/results/$RUN-*`)은 지우지 않는다.
