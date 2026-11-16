#!/usr/bin/env bash
# 부하 중 5초마다 서버 자원을 한 줄씩 찍는다. 다른 터미널에서 k6와 함께 돌린다.
# 쓰는 법: bash loadtest/watch.sh [관리 포트 주소] [compose 프로젝트 이름]  > loadtest/results/watch-<시나리오>.log
# db_tps = Postgres weathertalk DB의 커밋+롤백 수 증가분 / 지난 측정 뒤 지난 초. API TPS(k6 http_reqs)와 나란히 본다.
MGMT=${1:-http://localhost:18081}
PROJECT=${2:-weathertalk-loadtest}
metric() { curl -s "$MGMT/actuator/metrics/$1" | sed -n 's/.*"value":\([0-9.E+-]*\).*/\1/p' | head -1; }
psql_q() { docker exec "$PROJECT-postgres-1" psql -U weathertalk -tAc "$1" 2>/dev/null; }
prev_xact=""; prev_t=""
while true; do
  now=$(date +%s)
  xact=$(psql_q "select xact_commit + xact_rollback from pg_stat_database where datname = 'weathertalk'")
  db_tps="-"
  if [ -n "$prev_xact" ] && [ -n "$xact" ] && [ "$now" -gt "$prev_t" ]; then
    db_tps=$(( (xact - prev_xact) / (now - prev_t) ))
  fi
  prev_xact=$xact; prev_t=$now
  echo "== $(date +%T) db_tps=$db_tps hikari active=$(metric hikaricp.connections.active) pending=$(metric hikaricp.connections.pending)" \
    "jvm.threads=$(metric jvm.threads.live) heap=$(metric jvm.memory.used)" \
    "pg_conn=$(psql_q 'select count(*) from pg_stat_activity')" \
    "redis_ops=$(docker exec "$PROJECT-redis-1" redis-cli info stats 2>/dev/null | sed -n 's/instantaneous_ops_per_sec:\([0-9]*\).*/\1/p')"
  docker stats --no-stream --format '   {{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' | grep -E "^ +$PROJECT-"
  sleep 5
done
