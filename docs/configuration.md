# Configuration

Every setting of the backend and the worker, with its default. Settings are environment variables. Both programs also
read a `.env` file from the folder they are started in (real environment variables win over the file). Ready-made examples:
[`services/backend/.env.example`](../services/backend/.env.example), [`services/worker/.env.example`](../services/worker/.env.example) and
[`deploy/.env.example`](../deploy/.env.example) (for Docker Compose).

## Backend

### Database (required)

| Variable | Default | Meaning |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | Database host. |
| `POSTGRES_PORT` | `5432` | Database port. |
| `POSTGRES_DB` | `nimbus` | Database name. |
| `POSTGRES_USER` | `nimbus` | Database user. |
| `POSTGRES_PASSWORD` | `changeme` | Database password. **Change it** for anything beyond local development. |

The backend creates and upgrades its tables on start, using versioned migrations, and will not run if they do not match.

### Sign-in (required)

| Variable | Meaning |
|---|---|
| `GITHUB_CLIENT_ID` | Client ID of your GitHub OAuth App. |
| `GITHUB_CLIENT_SECRET` | Its client secret. Treat it like a password. |

The OAuth App's callback URL must be `<the address you open Nimbus at>/login/oauth2/code/github`.

### Sessions (optional)

| Variable | Default | Meaning |
|---|---|---|
| `REDIS_ENABLED` | `false` | `true` stores sign-in sessions in Redis, so they survive restarts and work across several backend instances. `false` keeps them in memory and needs no Redis. |
| `REDIS_HOST` | `localhost` | Redis host. |
| `REDIS_PORT` | `6379` | Redis port. |
| `REDIS_PASSWORD` | empty | Redis password, if any. |

Sessions last 30 minutes of inactivity when stored in Redis.

### Deployment behaviour (optional)

These control how deployment jobs are leased and taken back. Defaults are in the backend's `application.yaml`; as environment
variables they are written in upper case with underscores (for example `NIMBUS_DEPLOYMENTS_MAX_ATTEMPTS`).

| Setting | Default | Meaning |
|---|---|---|
| `nimbus.deployments.max-attempts` | `3` | How many times a job may be claimed in total. A job whose worker goes silent is queued again until this is reached, then it fails. |
| `nimbus.deployments.lease-duration` | `30m` | The fixed length of a job's lease. Heartbeats do not extend it. (Workers can override this for the jobs they claim; see below.) |
| `nimbus.deployments.heartbeat-timeout` | `2m` | A job whose last heartbeat is older than this is treated as abandoned. |
| `nimbus.deployments.reclaim-interval` | `30s` | How often abandoned jobs are taken back. |
| `nimbus.deployments.reclaim-enabled` | `true` | Turn the periodic take-back off (used by the tests). |

### Other

| Setting | Meaning |
|---|---|
| `SERVER_PORT` | The port the backend listens on (default `8080`). |
| `JAVA_TOOL_OPTIONS` | JVM options. The image sets `-XX:MaxRAMPercentage=75`, so it uses at most three quarters of the memory it is given. |

**Health:** `/actuator/health` (overall) and `/actuator/health/readiness` (ready to serve; includes the database and, when
enabled, Redis). Both are open to anyone; all other actuator endpoints are closed.

## Worker

### Database (required)

The same database as the backend.

| Variable | Meaning |
|---|---|
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Same meaning as for the backend. All five are required; the worker refuses to start and names any that are missing. |

### Identity and capacity

| Variable | Default | Meaning |
|---|---|---|
| `WORKER_POOL` | `default` | The pool the worker belongs to. Workers in a pool share default settings. |
| `WORKER_NAME` | empty | Ask for an exact worker name. Empty means "give me the next free one" (`default-0`, `default-1`, ...). |
| `WORKER_PARALLEL_JOBS` | `4` | How many jobs the worker runs at once (1 to 100). |
| `WORKER_LEASE_SECONDS` | `1800` | How long a job it claims is leased for (60 to 86400). |
| `WORKER_HTTP_PORT` | `8081` | The port for the worker's own HTTP endpoints. Give each worker on one machine a different one. |

`WORKER_PARALLEL_JOBS` and `WORKER_LEASE_SECONDS` are only the values a worker **starts with**. A stored setting for the
worker or its pool (set from the admin page) takes precedence, in this order:

1. the setting stored for that specific worker;
2. the setting stored for its pool;
3. the value from the environment above.

### What a job does

| Variable | Default | Meaning |
|---|---|---|
| `WORKER_RUNNER` | `fake` | `fake` waits and succeeds without deploying anything; `kubernetes` deploys to a cluster. |
| `WORKER_FAKE_JOB_SECONDS` | `15` | How long the `fake` runner takes per job (0 for instant). |

### Kubernetes runner (`WORKER_RUNNER=kubernetes`)

| Variable | Default | Meaning |
|---|---|---|
| `WORKER_KUBE_IN_CLUSTER` | `false` | `true` when the worker runs inside the target cluster: it uses its pod's service account. |
| `WORKER_KUBECONFIG` | the usual kubeconfig locations | A kubeconfig file, when not running in a cluster. |
| `WORKER_KUBE_CONTEXT` | none | **Required** when not running in a cluster: the context in the kubeconfig to deploy to. It is never defaulted, so a worker cannot deploy to the wrong cluster by accident. |
| `WORKER_DEPLOY_TIMEOUT_SECONDS` | `300` | How long a container gets to become ready (30 to 3600). |
| `WORKER_BASE_DOMAIN` | none | The domain public containers' addresses end in (for example `apps.example.com`, or `localhost` locally). |
| `WORKER_GATEWAY` | none | The shared Gateway public containers attach to, as `<namespace>/<name>`. |

`WORKER_BASE_DOMAIN` and `WORKER_GATEWAY` go together: set both or neither. Without them, a container marked Public fails to
deploy with a message saying so; everything else works.

On start the worker connects to the cluster and fails immediately, with the reason, if it cannot.

### Endpoints

| Endpoint | Meaning |
|---|---|
| `GET /healthz` | Liveness: answers `ok` while the process is up. |
| `GET /actuator/health` | Readiness, including the database. |
| `GET /actuator/info`, `/actuator/metrics`, `/actuator/ping` | Standard information. |
| `PUT /config` | Changes `parallelJobs` and `leaseSeconds` of this one worker, in memory. **It has no authentication**: never expose a worker's HTTP port to a network you do not trust. The admin page is the supported way to change these. |

## Settings you change while running

From **Admin, then Workers**, an admin can change how many jobs a worker runs at once, and its lease length, either for one
worker or as a default for a whole pool. Workers pick the change up on their next heartbeat (within about ten seconds), and
the page shows whether each one has caught up. Lowering the parallel-jobs limit does not stop jobs that are already
running, and a new lease length only affects jobs claimed afterwards.
