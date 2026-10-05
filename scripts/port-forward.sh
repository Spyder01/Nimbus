#!/usr/bin/env bash
# Forward nimbus postgres and redis to localhost for local backend development.
#
# Usage:
#   scripts/port-forward.sh
#
# Ports can be overridden: PG_PORT=5433 REDIS_PORT=6380 scripts/port-forward.sh
# (set POSTGRES_PORT / REDIS_PORT in services/backend/.env to match).
# Ctrl-C stops both forwards. Reconnects automatically if a forward drops
# (e.g. after a pod restart).

set -euo pipefail

NS="nimbus"
PG_PORT="${PG_PORT:-5432}"
REDIS_PORT="${REDIS_PORT:-6379}"

ctx="$(kubectl config current-context)"
if [[ "$ctx" != kind-* && "$ctx" != "${ALLOW_CONTEXT:-}" ]]; then
  echo "Refusing to run against context '$ctx' (expected kind-*). Set ALLOW_CONTEXT=$ctx to override." >&2
  exit 1
fi

forward() {
  local svc="$1" local_port="$2" remote_port="$3"
  while true; do
    kubectl -n "$NS" port-forward "svc/$svc" "$local_port:$remote_port" >/dev/null 2>&1 \
      || echo "[$svc] forward dropped, retrying in 2s..." >&2
    sleep 2
  done
}

trap 'kill 0' EXIT INT TERM

forward postgres-rw "$PG_PORT" 5432 &
forward redis-rw "$REDIS_PORT" 6379 &

echo "postgres -> localhost:$PG_PORT"
echo "redis    -> localhost:$REDIS_PORT"
echo "Ctrl-C to stop."
wait
