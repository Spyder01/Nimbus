# Getting started

What you need, and how to run Nimbus on your own machine. There are two ways:

- **[Run the published images with Docker Compose](#option-a-run-the-published-images-docker-compose)**: nothing to build,
  the quickest way to see Nimbus working.
- **[Run from source](#option-b-run-from-source)**: for working on Nimbus itself.

Both need a GitHub OAuth App for sign-in, so start with [Sign-in setup](#sign-in-setup-a-github-oauth-app).

## System requirements

### To run the published images (Option A)

| | Needed |
|---|---|
| **Operating system** | Linux, macOS or Windows with Docker (Docker Desktop or Docker Engine). |
| **Docker** | Docker Engine 24 or newer with **Docker Compose v2.20 or newer** (the `docker compose` command, not the older `docker-compose`). |
| **CPU and memory** | 2 CPU cores and about 4 GB of free memory for Nimbus itself (the database, Redis, the backend and three workers). A local Kubernetes cluster for deploying apps needs about 2 GB more. |
| **Disk** | About 2 GB for the images and data, plus whatever the apps you deploy need. |
| **Network** | Internet access to pull images, and to reach GitHub for sign-in. |
| **Browser** | A current version of Chrome, Edge, Firefox or Safari. |
| **Free ports** | `8080` (Nimbus). Nothing else is published to your machine. |

### To run from source (Option B), additionally

| Tool | Version | Used for |
|---|---|---|
| **JDK** | 25 | The backend. |
| **Node.js** | 22 (with npm) | The web application. |
| **Go** | 1.24 | The worker. |
| **PostgreSQL** | 16 | The database (Docker is the easiest way to run it). |
| **Redis** | 7, optional | Sign-in sessions. Off by default when running from source. |

### To deploy apps to a local Kubernetes cluster, additionally

| Tool | Used for |
|---|---|
| **kind** | A Kubernetes cluster that runs inside Docker. |
| **kubectl** | Talking to it. |
| **Helm** | Installing the Gateway controller. |

Nothing here is needed just to try the interface: the default workers use a stand-in runner that deploys nothing.

## Sign-in setup: a GitHub OAuth App

People sign in with GitHub, so Nimbus needs an OAuth App registered under a GitHub account you control. It is free and
takes a minute.

1. On GitHub open **Settings, then Developer settings, then OAuth Apps, then New OAuth App**.
2. Fill it in:
   - **Application name**: anything, for example "Nimbus (local)".
   - **Homepage URL**: `http://localhost:8080` (or `http://localhost:5173` if you run from source).
   - **Authorization callback URL**: must match exactly how you will open Nimbus:

     | You open Nimbus at | Callback URL |
     |---|---|
     | `http://localhost:8080` (Docker Compose) | `http://localhost:8080/login/oauth2/code/github` |
     | `http://localhost:5173` (from source, through the dev server) | `http://localhost:5173/login/oauth2/code/github` |

     An OAuth App has one callback URL, so if you use both ways, create two apps.
3. Create the app, then **Generate a new client secret**. Keep the **Client ID** and the **Client secret**: they go in
   your settings as `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET`. Treat the secret like a password.

## Option A: run the published images (Docker Compose)

The Compose files are in [`deploy/`](../deploy). They run PostgreSQL, Redis, the backend (which serves the web
application) and three workers, using images published to Docker Hub by the project's GitHub Actions workflows.

1. Go to the folder and create your settings file:

   ```bash
   cd deploy
   cp .env.example .env
   ```

2. Edit `.env` and fill in the values marked **REQUIRED**: your Docker Hub account name (where the `nimbus-backend` and
   `nimbus-worker` images are published), a database password you choose, and the GitHub client ID and secret from above.

3. Start everything:

   ```bash
   docker compose up -d
   ```

   The first start pulls the images. After about 20 seconds, `docker compose ps` shows everything healthy.

4. Open **http://localhost:8080** and sign in with GitHub.

5. [Make yourself the first super admin](#the-first-super-admin) to see the admin pages.

The full guide, including scaling workers, deploying to a real cluster, upgrading and backing up, is in
[Running with Docker](running-with-docker.md).

## Option B: run from source

You will run four things, each in its own terminal: the database (and optionally Redis), the backend, the web
application, and one or more workers.

### 1. The database

The easiest way is Docker:

```bash
docker run -d --name nimbus-postgres -p 5432:5432 \
  -e POSTGRES_USER=nimbus -e POSTGRES_PASSWORD=changeme -e POSTGRES_DB=nimbus postgres:16
```

(If you keep a local Kubernetes cluster for development, `scripts/nimbus.sh up` installs PostgreSQL and Redis into it, and
`scripts/port-forward.sh` makes them available on `localhost`. See the comments at the top of those scripts. They refuse to
run unless your current kubectl context is a `kind-*` cluster.)

Redis is optional. Without it the backend keeps sessions in memory, so you are signed out whenever it restarts.

### 2. The backend

```bash
cd services/backend
cp .env.example .env      # then fill in GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET
./gradlew bootRun
```

The backend reads `.env` from the folder you start it in, creates and upgrades the database tables itself, and listens
on port 8080. It needs JDK 25.

### 3. The web application

```bash
cd frontend
npm ci
npm start
```

Open **http://localhost:5173**. The dev server forwards API and sign-in requests to the backend on port 8080, so the
browser sees a single address (this is why the GitHub callback URL uses port 5173 in this mode).

### 4. Workers

```bash
cd services/worker
cp .env.example .env
go run .
```

Start more workers in more terminals; each machine-local worker needs its own HTTP port:

```bash
WORKER_HTTP_PORT=8082 go run .
WORKER_HTTP_PORT=8083 go run .
```

Each takes the next free name (`default-0`, `default-1`, ...). By default workers use the stand-in runner, so a deployment
completes after a few seconds without running anything. To deploy to a real cluster, continue below.

## Deploying to a local Kubernetes cluster

To make workers really run containers, give them a cluster. The repository includes a script that creates a local one
(with the Gateway that gives public containers a web address):

```bash
scripts/dev-cluster.sh up
```

It creates a kind cluster called `nimbus-dev`, installs the Gateway controller, and prints the exact settings to run a
worker with. They are:

| Setting | Value |
|---|---|
| `WORKER_RUNNER` | `kubernetes` |
| `WORKER_KUBE_CONTEXT` | `kind-nimbus-dev` |
| `WORKER_BASE_DOMAIN` | `localhost` |
| `WORKER_GATEWAY` | `nimbus-gateway/nimbus` |

Start workers with those set (in `services/worker/.env` or in the environment). Public containers are then reachable at
`http://<container>-<app id prefix>.localhost`. Chrome, Firefox and `curl` resolve `*.localhost` to your machine on their
own; if your browser does not, use `localtest.me` as the base domain instead.

`scripts/dev-cluster.sh status` shows what is running and `scripts/dev-cluster.sh down` removes the cluster. The script only
ever touches the cluster it creates, and leaves your current kubectl context as it found it.

## The first super admin

Everyone who signs in starts as a regular member. The admin pages (workers and members) need a higher role, and the
highest role, **super admin**, can only be given in the database, so that no request can ever create one. After you have
signed in once (so that your account exists), run the script from the repository root with your GitHub username:

```bash
scripts/promote-super-admin.sh YOUR-GITHUB-USERNAME
```

It finds the database by itself: the Docker Compose stack if it is running, otherwise a container named `nimbus-postgres` (the
one from step 1 of running from source), otherwise a local `psql` using the `POSTGRES_*` settings from your environment or
`services/backend/.env`. To point it at a particular container, add `--container NAME`. It is safe to run again, and it tells
you if the person has not signed in yet.

Reload the page. You will see **Workers** and **Members** under *Admin* in the sidebar. From the Members page a super admin can
make other people admins (and take it away again). Role changes apply immediately.

## Try it: the example app

[`examples/hello-ui.yaml`](../examples/hello-ui.yaml) is a small app with a public web page and a private service behind it.

1. On the dashboard choose **Import YAML** and pick the file.
2. Open the app and press **Deploy**.
3. With a Kubernetes runner and a Gateway (see above), an **Open** button appears when it is done. Reloading the page
   shows its two replicas taking turns. With the stand-in runner, the deployment completes but nothing is running.

## Where things are

| What | Address |
|---|---|
| Nimbus (Docker Compose) | http://localhost:8080 |
| Nimbus (from source, web dev server) | http://localhost:5173 |
| Backend API (from source) | http://localhost:8080 |
| Backend health | `/actuator/health` (and `/actuator/health/readiness`) |
| A worker's health | `http://localhost:<worker port>/healthz` (default port 8081) |

## Running the tests

| Part | Command (from its folder) |
|---|---|
| Backend | `./gradlew test` in `services/backend` (needs the database from step 1 running, and creates its own temporary data) |
| Web application | `npm run lint` and `npm run build` in `frontend` |
| Worker | `go vet ./...` and `go test ./...` in `services/worker` |

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| Docker Compose says a variable is required | A value marked REQUIRED in `deploy/.env` is empty. The message names which one. |
| Signing in leads to a GitHub error page about the redirect URI | The callback URL on your GitHub OAuth App does not match the address you opened Nimbus at (including the port). |
| Backend fails at start with a database error | The database is not reachable or the password differs. From source, check the `POSTGRES_*` values in `services/backend/.env`. |
| `docker compose ps` shows the backend unhealthy | Look at `docker compose logs backend`. The usual causes are the database not being ready yet or a missing `GITHUB_*` value. |
| The Workers page is empty | Workers register after the backend has created the tables. Check they are running and can reach the database. |
| Deployments stay "Queued" | No worker is running, or all of them are at their parallel-jobs limit. Check the Workers page. |
| A job fails with "not supported yet" | The design uses a volume (stateful container) or a secret value, which the Kubernetes runner does not deploy yet. |
| A job fails saying the worker has no `WORKER_BASE_DOMAIN` | A container is marked Public but the worker was not given a domain and Gateway. See the section on local clusters above. |
| Port 80 is in use when creating the dev cluster | The Gateway needs port 80 on your machine. Stop whatever is using it. |
| Public address does not open | Some browsers do not resolve `*.localhost`; use `localtest.me` as `WORKER_BASE_DOMAIN`, or add a hosts entry. |
