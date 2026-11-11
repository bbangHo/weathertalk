#!/usr/bin/env bash
# 부하 중 5초마다 서버 자원을 한 줄씩 찍는다. 다른 터미널에서 k6와 함께 돌린다.
# 쓰는 법: bash loadtest/watch.sh [관리 포트 주소] [compose 프로젝트 이름]  > loadtest/results/watch-<시나리오>.log
MGMT=${1:-http://localhost:18081}
PROJECT=${2:-backend}
metric() { curl -s "$MGMT/actuator/metrics/$1" | sed -n 's/.*"value":\([0-9.E+-]*\).*/\1/p' | head -1; }
while true; do
  echo "== $(date +%T) hikari active=$(metric hikaricp.connections.active) pending=$(metric hikaricp.connections.pending)" \
    "jvm.threads=$(metric jvm.threads.live) heap=$(metric jvm.memory.used)" \
    "pg_conn=$(docker exec "$PROJECT-postgres-1" psql -U weathertalk -tAc 'select count(*) from pg_stat_activity' 2>/dev/null)" \
    "redis_ops=$(docker exec "$PROJECT-redis-1" redis-cli info stats 2>/dev/null | sed -n 's/instantaneous_ops_per_sec:\([0-9]*\).*/\1/p')"
  docker stats --no-stream --format '   {{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' | grep -E "^ +$PROJECT-"
  sleep 5
done
