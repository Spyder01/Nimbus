# syntax=docker/dockerfile:1
# Builds the worker as a static binary and ships it on a minimal, non-root base image.
FROM --platform=$BUILDPLATFORM golang:1.24 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .

# Shown by /actuator/info. CI passes the git tag or short sha.
ARG VERSION=dev
ARG TARGETOS
ARG TARGETARCH
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/worker .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/worker /worker

# Required at run time (no defaults, the worker refuses to start without them):
#   POSTGRES_HOST  POSTGRES_PORT  POSTGRES_DB  POSTGRES_USER  POSTGRES_PASSWORD
# Optional, with the defaults below. Parallel jobs and lease length can also be changed while running, from the admin
# page or with PUT /config; WORKER_NAME asks for an exact worker name instead of being given one.
#
# What a job does is chosen by WORKER_RUNNER: "fake" (the default; deploys nothing) or "kubernetes". The Kubernetes
# runner needs WORKER_KUBE_IN_CLUSTER=true (the pod's service account) or WORKER_KUBECONFIG plus WORKER_KUBE_CONTEXT,
# and takes WORKER_DEPLOY_TIMEOUT_SECONDS (30-3600, default 300) for how long a container gets to become ready.
# Public containers also need WORKER_BASE_DOMAIN (e.g. apps.example.com) and WORKER_GATEWAY (<namespace>/<name> of the
# Gateway API Gateway to attach to); without both, a public container fails to deploy with a message saying so.
ENV WORKER_POOL=default \
    WORKER_PARALLEL_JOBS=4 \
    WORKER_LEASE_SECONDS=1800 \
    WORKER_HTTP_PORT=8081

EXPOSE 8081
# Liveness: GET /healthz. Readiness and DB health: GET /actuator/health.
ENTRYPOINT ["/worker"]
