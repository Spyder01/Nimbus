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
ENV WORKER_POOL=default \
    WORKER_PARALLEL_JOBS=4 \
    WORKER_LEASE_SECONDS=1800 \
    WORKER_HTTP_PORT=8081

EXPOSE 8081
# Liveness: GET /healthz. Readiness and DB health: GET /actuator/health.
ENTRYPOINT ["/worker"]
