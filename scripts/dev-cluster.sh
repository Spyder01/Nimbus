#!/usr/bin/env bash
# The local kind cluster that runs users' apps, with the Gateway that gives public containers a web address.
# (The Nimbus services themselves, postgres and redis, are scripts/nimbus.sh.)
#
# Usage:
#   scripts/dev-cluster.sh up        create the cluster, install the Gateway, and print how to run a worker against it
#   scripts/dev-cluster.sh down      delete the cluster
#   scripts/dev-cluster.sh status    show the cluster, the Gateway and the apps running on it
#
# Public containers are served at http://<container>-<app id>.localhost (port 80). Chrome, Firefox and curl resolve
# *.localhost to this machine by themselves; other tools may need *.localtest.me instead (WORKER_BASE_DOMAIN).
#
# Safety: it only ever touches the kind cluster named below, always by explicit context, and it leaves your current
# kubectl context as it found it.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER="${CLUSTER_NAME:-nimbus-dev}"
CTX="kind-$CLUSTER"
EG_VERSION="${EG_VERSION:-v1.9.2}"   # Envoy Gateway, which also brings the Gateway API CRDs
TIMEOUT="${TIMEOUT:-300s}"

need() { command -v "$1" >/dev/null || { echo "Missing '$1'. Install it first." >&2; exit 1; }; }

k() { kubectl --context "$CTX" "$@"; }

up() {
  need kind; need kubectl; need helm
  local before
  before="$(kubectl config current-context 2>/dev/null || true)"

  if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    echo "Cluster '$CLUSTER' already exists."
  else
    if lsof -nP -iTCP:80 -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Port 80 on this machine is in use, and the Gateway needs it." >&2
      exit 1
    fi
    echo "Creating cluster '$CLUSTER'..."
    kind create cluster --name "$CLUSTER" --config "$ROOT/k8/kind/nimbus-dev.yaml" --wait 120s
    # kind switches to the new cluster; put things back the way they were.
    if [[ -n "$before" && "$before" != "$CTX" ]]; then kubectl config use-context "$before" >/dev/null; fi
  fi

  echo "Installing Envoy Gateway $EG_VERSION (with the Gateway API CRDs)..."
  helm --kube-context "$CTX" upgrade --install eg oci://docker.io/envoyproxy/gateway-helm \
    --version "$EG_VERSION" -n envoy-gateway-system --create-namespace --wait --timeout "$TIMEOUT" >/dev/null
  k wait --for=condition=Available deployment/envoy-gateway -n envoy-gateway-system --timeout="$TIMEOUT"

  echo "Creating the Nimbus Gateway..."
  k apply -f "$ROOT/k8/gateway/namespace.yaml"
  k apply -f "$ROOT/k8/gateway/gateway.yaml"
  k wait --for=condition=Programmed gateway/nimbus -n nimbus-gateway --timeout="$TIMEOUT"

  cat <<MSG

Ready. Run a worker against it with:

  WORKER_RUNNER=kubernetes \\
  WORKER_KUBE_CONTEXT=$CTX \\
  WORKER_BASE_DOMAIN=localhost \\
  WORKER_GATEWAY=nimbus-gateway/nimbus \\
  go run .          # in services/worker

A public container "web" of app 3f7b8e88-... is then at http://web-3f7b8e88.localhost
MSG
}

down() {
  need kind
  kind delete cluster --name "$CLUSTER"
}

status() {
  k get nodes
  echo; k get gateway -n nimbus-gateway
  echo; k get httproutes -A
  echo; k get deploy,svc -A -l app.kubernetes.io/managed-by=nimbus
}

case "${1:-}" in
  up)     up ;;
  down)   down ;;
  status) status ;;
  *)      sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
