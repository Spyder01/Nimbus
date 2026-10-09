# Nimbus backend

The API and the owner of the database. Kotlin on Spring Boot 4 and JDK 25. It serves the REST API and, in production, the
built web application. It never deploys anything itself: it plans deployments into jobs that workers carry out. See the
[architecture](../../docs/architecture.md) for the whole picture.

## Running it

You need JDK 25 and a PostgreSQL 16 database ([Getting started](../../docs/getting-started.md#option-b-run-from-source)).

```bash
cp .env.example .env      # fill in the GitHub OAuth values
./gradlew bootRun         # listens on port 8080
./gradlew test            # the test suite (uses the database; its data rolls back)
./gradlew bootJar         # builds build/libs/app.jar, with the web application embedded if frontend/dist exists
```

The jar is what the Docker image packages (`Dockerfile`); the GitHub Actions workflow builds the web application first, so the
published image contains both. Every setting is listed in [Configuration](../../docs/configuration.md).

## How it is organised

Each area is a package with the same layers: controllers (HTTP), DTOs (what crosses the wire), services (the rules),
repositories (database access) and entities.

| Package | Responsibility |
|---|---|
| `users` | Sign-in, profile, roles, and the members admin. |
| `apps` | Apps, designs and saved versions, YAML import and export, validation of a design, planning a deployment into jobs, the rules that move jobs and deployments through their states, and the worker-facing job operations. |
| `workers` | The admin view of workers and pools, and their settings. |
| `images` | The curated image catalog, checked at start-up. |
| `configuration` | Security (sign-in, CSRF, per-request roles), sessions, deployment settings, serving the web application. |
| `shared` | The common API error type. |

Resources: `application.yaml` (settings and defaults), `db/migration/` (the versioned database migrations, V1 onward) and
`images/catalog.json` (the image catalog).

## Things worth knowing

- **The backend owns the schema.** Migrations run on start and the app refuses to start if the database does not match. Workers
  read and write the same tables but never change them.
- **A deployment's state is decided in one place.** After any change to a job, one piece of logic settles the deployment and the
  app, so they cannot disagree.
- **Locks are taken in a fixed order** (app, deployment, job), the same order the worker uses, so a cancel racing a worker cannot
  deadlock.
- **Roles come from the database on every request**, not from the session, so changes apply at once.
- **Tests run against a real database**, not a mock, and roll back, so the schema, queries and locking are exercised for real.
