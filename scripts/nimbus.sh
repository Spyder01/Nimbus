#!/usr/bin/env bash
# Bring the nimbus k8s services (postgres, redis) up or down.
#
# Usage:
#   scripts/nimbus.sh up              apply manifests and wait until ready
#   scripts/nimbus.sh down            remove services, KEEP data (PVCs)
#   scripts/nimbus.sh down --purge    remove services AND delete data (PVCs)
#   scripts/nimbus.sh status          show pods, services, volumes
#
# Safety: refuses to run unless the kubectl context starts with "kind-".
# Override with ALLOW_CONTEXT=<context-name>.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
K8S_DIR="$ROOT/k8"
NS="nimbus"
TIMEOUT="${TIMEOUT:-120s}"

ctx="$(kubectl config current-context)"
if [[ "$ctx" != kind-* && "$ctx" != "${ALLOW_CONTEXT:-}" ]]; then
  echo "Refusing to run against context '$ctx' (expected kind-*)." >&2
  echo "Set ALLOW_CONTEXT=$ctx to override." >&2
  exit 1
fi
echo "Using context: $ctx"

up() {
  kubectl apply -f "$K8S_DIR/namespace.yaml"
  kubectl apply -R -f "$K8S_DIR/postgres" -f "$K8S_DIR/redis"
  echo "Waiting for pods to be ready (timeout $TIMEOUT)..."
  # Pods are created by the StatefulSet controller, so wait for them to exist first.
  for app in postgres redis; do
    until kubectl -n "$NS" get pod -l "app=$app" -o name 2>/dev/null | grep -q .; do sleep 1; done
    kubectl -n "$NS" wait --for=condition=Ready pod -l "app=$app" --timeout="$TIMEOUT"
  done
  status
}

down() {
  local purge="${1:-}"
  kubectl delete -R -f "$K8S_DIR/postgres" -f "$K8S_DIR/redis" --ignore-not-found
  if [[ "$purge" == "--purge" ]]; then
    echo "Purging PVCs (data will be lost)..."
    kubectl -n "$NS" delete pvc -l app=postgres --ignore-not-found
    kubectl -n "$NS" delete pvc -l app=redis --ignore-not-found
  else
    echo "Data kept. PVCs remain; use 'down --purge' to delete them."
  fi
}

status() {
  kubectl -n "$NS" get pods,svc,pvc
}

case "${1:-}" in
  up)     up ;;
  down)   down "${2:-}" ;;
  status) status ;;
  *)
    echo "Usage: $0 {up|down [--purge]|status}" >&2
    exit 2
    ;;
esac
