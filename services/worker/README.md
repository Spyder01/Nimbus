# Nimbus worker

Claims deployment jobs from the database and deploys one container per job. Written in Go. Run as many as you like: they
are interchangeable and can come and go at any time. See the [architecture](../../docs/architecture.md#workers) for how workers,
leases and cancellation work.

## Running it

You need Go 1.24 and the database the backend uses ([Getting started](../../docs/getting-started.md#option-b-run-from-source)).

```bash
cp .env.example .env     # database settings; everything else is optional
go run .                 # or: WORKER_HTTP_PORT=8082 go run .  for a second worker on the same machine
go vet ./... && go test ./...
```

By default it uses a stand-in runner that deploys nothing. To deploy to a real cluster set `WORKER_RUNNER=kubernetes` and the
cluster settings: see [Configuration](../../docs/configuration.md#kubernetes-runner-worker_runnerkubernetes). The published image is
built from `Dockerfile` by the GitHub Actions workflow.

## How it is organised

| Package | Responsibility |
|---|---|
| `main.go` | Starts the three things a worker does: the HTTP endpoints, the loop that claims jobs, and the keep-alive; holds each job and keeps its lease alive. |
| `internal/config` | Reads settings; keeps the two that can change while running (parallel jobs, lease length). |
| `internal/registry` | The worker's name (a slot in a pool), its slot lease, and the stored settings. |
| `internal/membership` | Registers, renews the slot, applies changed settings, and releases the slot on shutdown. |
| `internal/jobs` | Claims a job within the quota, renews its lease, and finishes it (completed, failed, or stopped on cancel). |
| `internal/runner` | What a job does: the stand-in, or the Kubernetes runner. |
| `internal/kube` | Turns a container into Kubernetes objects, applies them, waits for the rollout, and handles public addresses. |
| `internal/api`, `internal/db` | The small HTTP surface (`/healthz`, `/config`) and the database connection. |

## Things worth knowing

- **It talks to the database, not to the backend.** The backend owns the schema; the worker only uses it.
- **It never claims more than its limit**, counted from the database, so the count cannot drift.
- **It never deploys to "the current context".** A kubeconfig is used only with an explicitly named context.
- **A stop is undone only when a user cancelled.** If a lease was lost or the worker is shutting down, it removes nothing,
  because another worker may be deploying the same container.
- **Its `PUT /config` endpoint is unauthenticated.** Keep a worker's HTTP port off untrusted networks.
