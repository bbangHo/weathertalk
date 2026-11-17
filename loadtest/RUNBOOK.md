# 밤샘 부하테스트 실행 지시서 (미니 PC)

> **사람이 자는 동안 에이전트(Claude 세션)가 혼자 따라 하는 순서**다. 중간에 사람에게 묻지 않는다. 막히면 그 단계를 건너뛰고 이유를 보고서에 적는다.
> 배경·합격 기준의 근거는 `loadtest/README.md`, 시나리오 설계는 로컬 계획서 `docs/plan/load-test-plan.md`.
> 미니 PC 접속 방법은 `minipc` 스킬. 에이전트는 **개발 PC에서 SSH로** 미니 PC에 명령을 보낸다.

## 미니 PC 사실 (2026-11-17 확인)

| 항목 | 값 |
|---|---|
| OS·자원 | **Windows**, 논리 CPU 4개, 메모리 7.9GB(도커만 켠 상태 여유 약 2.6GB) |
| 접속 | `ssh -p 22222 user@bbangho-server.duckdns.org` (Windows OpenSSH, 자주 끊김 → 명령마다 3번까지 재시도) |
| 셸 | 원격 명령은 **cmd**. bash가 필요하면 `"C:\Program Files\Git\bin\bash.exe"` |
| 저장소 클론 | `C:\Users\user\weathertalk` (브랜치 `claude/load-test-bf35bf`, 2026-11-17 클론). 미니 PC에서 `gradlew.bat bootJar` 빌드 확인(1분 10초) |
| 미리보기(데모) | `C:\Users\user\weathertalk-preview` (app.jar + compose, 프로젝트 `weathertalk-preview`, 포트 `127.0.0.1:8080`). 확인 당시 **꺼져 있음** |
| 다른 부하테스트 | 다른 프로젝트가 `C:\Users\user\loadtest`·컨테이너 `lt-*`·예약 작업 `lt-docker`를 쓴다. **건드리지 않는다** |
| 다른 운영 서비스 | `lsp-prod-*` 컨테이너 5개(caddy·web·backend·postgres·mediamtx)가 **상시 운영 중**(2026-11-17 확인). CPU·메모리를 같이 쓴다 |
| 이미지 | 필요한 이미지 5개(postgis/postgis:16-3.4, redis:7.4, eclipse-temurin:21-jre, node:22-alpine, grafana/k6:2.3.0) **모두 있음**(2026-11-17). SSH에서는 `docker pull`이 안 되니 새 이미지가 필요하면 멈추고 보고서에 적는다 |

사람이 미리 할 일은 없다. 코드와 jar는 에이전트가 2절에서 클론을 최신으로 받고 직접 빌드한다.

## 0. 지켜야 할 것 (어기면 즉시 멈춘다)

1. **부하는 미니 PC 안의 부하테스트 백엔드(`weathertalk-loadtest`)에만** 준다. k6는 compose 안에서 `http://backend:8080`으로만 쏜다. `ALLOW_REMOTE=1`을 쓰지 않는다.
2. **데모·다른 프로젝트를 건드리지 않는다.** `weathertalk-preview`·`lt-*`·**`lsp-prod-*`(운영 중)** 컨테이너를 멈추거나 지우거나 재시작하지 않는다. `C:\Users\user\loadtest`와 예약 작업 `lt-docker`를 쓰지 않는다. `down`은 **`-p weathertalk-loadtest`로만** 한다.
3. **외부 API를 부르지 않는다.** 기상청·에어코리아는 compose 안의 `mock`으로만 간다. 실제 키를 어디에도 넣지 않는다.
4. **다른 부하테스트와 겹치지 않는다.** 시작 전과 시나리오마다 `lt-*` 컨테이너가 떠 있으면 시작하지 않는다(이미 돌고 있으면 그 시나리오를 끝내고 멈춘다).
5. **데모나 운영 서비스가 느려지면 멈춘다** (3절). 운영 중인 `lsp-prod-*`가 같은 기계에 있으므로, 부하 중 그 컨테이너가 재시작·종료되면 즉시 k6를 멈추고 9절로 간다.
6. 코드·설정을 고치지 않는다. 커밋·푸시하지 않는다. 결과는 미니 PC `results\`와 개발 PC `loadtest/results/`에만 쓴다.

## 1. 실행 목록

| 순서 | ID | 시나리오 | SCENARIO / 조건 | 시간 | 회차 | 다음으로 가는 조건 |
|---|---|---|---|---|---|---|
| 1 | LT-01 | smoke | `smoke` | 1분 | 1 | 종료 코드 0 + check 100%. 아니면 **전체 중단** |
| 2 | LT-02 | load 20 TPS | `load` | 13분 | 2 | 기록하고 계속 |
| 3 | LT-03 | 느린 기상청 load | `load`, **DB 초기화 + 모의 서버 800ms** | 13분 | 2 | 계속 |
| 4 | LT-04 | spike 60 TPS | `spike` (모의 서버 0ms로 되돌린 뒤) | 5분 20초 | 2 | 계속 |
| 5 | LT-05 | stress ~200 TPS | `stress`, **DB 초기화** | 최대 21분 | 1 | 마지막 |

- 회차·시나리오 사이에 **2분** 쉰다. 같은 시나리오 2회의 홈 p95 차이가 10%를 넘으면 1회 더 돌린다.
- 예상 총 소요 약 2시간 30분. 8GB 기계라 stress 결과는 '이 기계에서의 한계'로 읽는다.

## 2. 사전 점검

아래에서 `SSH`는 `ssh -p 22222 user@bbangho-server.duckdns.org`, `W`는 `C:\Users\user\weathertalk\loadtest`.

먼저 클론을 최신으로 받고 백엔드를 빌드한다(2분 안팎):

```bash
SSH "cd /d C:\Users\user\weathertalk && git pull --ff-only && git log --oneline -1"
SSH "cd /d C:\Users\user\weathertalk\backend && gradlew.bat bootJar -x test --console=plain"      # BUILD SUCCESSFUL 이어야 한다
SSH "cd /d W && if not exist results mkdir results"
```

`git pull`이 충돌·실패하면(누가 클론에서 파일을 고친 것) 고치지 말고 보고서에 적고 끝낸다.

```bash
SSH "docker images --format {{.Repository}}:{{.Tag}}"     # node:22-alpine, grafana/k6:2.3.0 이 있어야 한다
SSH "docker ps --format {{.Names}}"                        # lt-* 가 있으면 0절 4번 → 중단
SSH "cd /d W && docker compose -f compose.minipc.yml config --quiet && echo CONFIG_OK"
```

- 파일·이미지가 없으면 '사람이 미리 할 일'이 안 된 것이다 → 보고서에 적고 끝낸다.
- 여유 메모리 확인: `SSH "powershell -NoProfile -Command [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1KB)"` — **1500(MB) 미만이면 중단**.
- 실행 이름: `RUN=$(date +%Y%m%d-%H%M)`. 결과 파일 앞에 붙인다. 개발 PC에 `mkdir -p loadtest/results`.

## 3. 데모·운영 서비스 감시

운영 서비스: 시작 전에 `SSH "docker ps --filter name=lsp-prod --format {{.Names}}"`로 떠 있는 개수를 적어 두고, 부하 중 1분마다 같은 명령으로 개수가 줄지 않았는지 본다. 줄었으면 0절 5번대로 멈춘다.

데모:

```bash
SSH "docker ps --filter name=weathertalk-preview-backend --format {{.Status}}"
```

- 비어 있으면 데모가 꺼져 있다 → 감시하지 않는다(보고서에 '데모 꺼짐' 기록).
- 떠 있으면 부하 중 1분마다 잰다(개발 PC에서 백그라운드로):
  ```bash
  while true; do t=$(SSH "curl -s -o NUL -w \"%{http_code} %{time_total}\" http://127.0.0.1:8080/api/v1/terms"); echo "$(date +%T) $t" >> loadtest/results/$RUN-demo.log; sleep 60; done
  ```
  응답 코드가 200이 아니거나 3초 이상인 줄이 **2번 연속**이면 돌던 k6를 멈추고(`docker stop weathertalk-loadtest-k6-run`) 9절로 간다.

## 4. 기동

```bash
SSH "cd /d W && docker compose -f compose.minipc.yml up -d postgres redis mock backend"
SSH "docker ps --format {{.Names}} | findstr weathertalk-loadtest"     # 4개가 weathertalk-loadtest-* 로 떠야 한다
```

기동 대기 (처음엔 동네 기준 데이터 3,564건 적재로 몇 분 걸린다. 최대 10분):

```bash
for i in $(seq 1 60); do SSH "curl -sf http://127.0.0.1:18081/actuator/health" && break; sleep 10; done
SSH "curl -s \"http://127.0.0.1:18090/counts\""      # 모의 서버 응답 확인
```

10분 안에 안 뜨면 `SSH "cd /d W && docker compose -f compose.minipc.yml logs --tail 80 backend"`를 보고서에 붙이고 9절로 간다.

## 5. 시나리오 한 회차

**자원 기록** (개발 PC에서 백그라운드로, 회차가 끝나면 끈다. 끊기면 다시 붙인다):

```bash
SSH "\"C:\Program Files\Git\bin\bash.exe\" C:/Users/user/weathertalk/loadtest/watch.sh http://127.0.0.1:18081 weathertalk-loadtest" > loadtest/results/$RUN-<ID>-<회차>-watch.log
```

**k6** — SSH가 끊겨도 계속 돌도록 분리 실행하고, 끝날 때까지 기다린다:

```bash
SSH "cd /d W && docker compose -f compose.minipc.yml run -d --name weathertalk-loadtest-k6-run -e SCENARIO=<시나리오> k6 run --summary-export /results/$RUN-<ID>-<회차>.json /scripts/weathertalk.js"
until [ "$(SSH "docker inspect -f {{.State.Status}} weathertalk-loadtest-k6-run")" = "exited" ]; do sleep 30; done
SSH "docker inspect -f {{.State.ExitCode}} weathertalk-loadtest-k6-run"                       # 0 합격 / 99 기준 미달 / 그 밖 오류
SSH "docker logs weathertalk-loadtest-k6-run" > loadtest/results/$RUN-<ID>-<회차>.txt 2>&1
SSH "curl -s http://127.0.0.1:18090/counts" > loadtest/results/$RUN-<ID>-<회차>-mock.json
SSH "docker rm weathertalk-loadtest-k6-run"
scp -P 22222 "user@bbangho-server.duckdns.org:C:/Users/user/weathertalk/loadtest/results/$RUN-<ID>-<회차>.json" loadtest/results/
```

- 종료 코드 **99**는 합격 기준 미달이다. 실패가 아니라 결과다 — 기록하고 다음으로 간다(LT-01만 예외: 중단).
- 로그에 `setup()` 실패(동네 목록·수도권 몫·로그인)가 있으면 그 메시지를 보고서에 적고 **전체 중단**.
- 시나리오를 시작하기 전마다 `lt-*` 컨테이너가 없는지 다시 본다.

## 6. DB 초기화와 모의 서버 지연

DB 초기화 (LT-03, LT-05 전):

```bash
SSH "cd /d W && docker compose -f compose.minipc.yml down -v"
```

그다음 4절 기동을 다시 한다.

모의 서버 지연 바꾸기 (LT-03 전에 800, LT-03 끝나고 0):

```bash
SSH "cd /d W && set MOCK_DELAY_MS=800&& docker compose -f compose.minipc.yml up -d --force-recreate mock"
SSH "cd /d W && set MOCK_DELAY_MS=0&& docker compose -f compose.minipc.yml up -d --force-recreate mock"
```

(`set X=800&&`는 붙여 쓴다. 띄우면 값 끝에 공백이 들어간다.) 바꾼 뒤 `SSH "docker logs weathertalk-loadtest-mock-1"` 첫 줄에 `지연 800ms`가 보이는지 확인한다.

## 7. 순서 요약

```
2 점검 → 3 데모 감시 → 4 기동
→ LT-01 ×1 (실패면 9)
→ LT-02 ×2
→ 6 DB 초기화 → 4 기동 → 모의 서버 800ms → LT-03 ×2 → 모의 서버 0ms
→ LT-04 ×2
→ 6 DB 초기화 → 4 기동 → LT-05 ×1
→ 9 정리 → 8 보고서
```

## 8. 보고서

개발 PC `loadtest/results/$RUN-report.md`에 쓴다. 숫자는 k6 JSON·watch 로그에서 그대로 옮긴다(추정하지 않는다).

1. **한 줄 결론**: LT-02 합격 여부, 한계 TPS(LT-05), 먼저 무너진 자원.
2. **표** (시나리오·회차마다 한 줄):

   | 시나리오 | 회차 | 실제 TPS | DB TPS(평균) | 오류율 | 홈 p95 / p99 | 조회 p95 | 쓰기 p95 | Hikari pending 최대 | Postgres CPU 최대 | dropped | 판정 |
   |---|---|---|---|---|---|---|---|---|---|---|---|

   - 실제 TPS = JSON `metrics.http_reqs.rate`. DB TPS = watch 로그 `db_tps` 평균(부하 유지 구간만). 홈 = `http_req_duration{kind:home}`.
   - 판정: 합격 / 불합격(어긴 기준) / 중단(이유).
3. **LT-03**: 홈 p99, `home 섹션 실패 없음` check 비율, 모의 서버 호출 수.
4. **LT-04**: 몰림 뒤 p95가 몰림 전 수준으로 돌아오기까지 걸린 시간(대략).
5. **LT-05**: 오류율 1% 또는 홈 p99 2초를 처음 넘은 단계(TPS)와 그때 Hikari pending·Postgres CPU·JVM 스레드.
6. **데모**: 꺼짐 / 경고 수.
7. **건너뛰거나 중단한 단계와 이유.**
8. **백엔드 경고**: `SSH "cd /d W && docker compose -f compose.minipc.yml logs backend"`에서 ERROR·WARN 상위 5종.

## 9. 정리 (항상, 중단했을 때도)

```bash
SSH "docker rm -f weathertalk-loadtest-k6-run"                        # 남아 있으면
SSH "cd /d W && docker compose -f compose.minipc.yml down -v"
SSH "docker ps -a --format \"{{.Names}} {{.Status}}\""                  # weathertalk-preview·lt-* 상태가 시작 때와 같은지 보고서에 적는다
```

개발 PC의 백그라운드 감시(watch·데모)를 끈다. 결과 파일은 지우지 않는다.
