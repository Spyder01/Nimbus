#!/usr/bin/env bash
# Make someone a super admin, by their GitHub username.
#
# The super admin role can only be given in the database (so that no request can ever create one), which is what this
# does. The person must have signed in to Nimbus at least once, so that their account exists. The change takes effect
# immediately: they only need to reload the page.
#
# Usage:
#   scripts/promote-super-admin.sh <github-username>
#
# Options:
#   --container NAME   run against this PostgreSQL container
#   --project NAME     Docker Compose project name to look for (default: nimbus, or $COMPOSE_PROJECT_NAME)
#   -h, --help         show this help
#
# How it reaches the database, in this order:
#   1. the container given with --container;
#   2. a running Compose stack's "postgres" service (deploy/docker-compose.yml);
#   3. a running container named "nimbus-postgres" (the one in docs/getting-started.md);
#   4. a local psql, using POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DB, POSTGRES_USER and POSTGRES_PASSWORD from the
#      environment or from services/backend/.env.
#
# Safe to run again: someone who is already a super admin is left as they are.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGIN=""
CONTAINER=""
PROJECT="${COMPOSE_PROJECT_NAME:-nimbus}"

die() { echo "Error: $*" >&2; exit 1; }
usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)   usage; exit 0 ;;
    --container) [[ $# -ge 2 ]] || die "--container needs a container name"; CONTAINER="$2"; shift 2 ;;
    --project)   [[ $# -ge 2 ]] || die "--project needs a project name"; PROJECT="$2"; shift 2 ;;
    -*)          die "Unknown option '$1' (see --help)" ;;
    *)           [[ -z "$LOGIN" ]] || die "Give just one username"; LOGIN="$1"; shift ;;
  esac
done
[[ -n "$LOGIN" ]] || { usage >&2; exit 1; }

# A GitHub username: letters, digits and single hyphens, at most 39 characters. Anything else is refused outright,
# and the name is passed to the database as a bound value rather than pasted into SQL.
[[ "$LOGIN" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,38})$ ]] || die "'$LOGIN' is not a valid GitHub username."

# --- find a way to the database -------------------------------------------------------------------------------------

MODE=""
if [[ -z "$CONTAINER" ]] && command -v docker >/dev/null 2>&1; then
  CONTAINER="$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" \
                  --filter "label=com.docker.compose.service=postgres" --filter status=running 2>/dev/null | head -n 1)"
  [[ -n "$CONTAINER" ]] || CONTAINER="$(docker ps -q --filter "name=^nimbus-postgres$" --filter status=running 2>/dev/null | head -n 1)"
fi
if [[ -n "$CONTAINER" ]]; then
  command -v docker >/dev/null 2>&1 || die "Docker is needed to reach the container '$CONTAINER'."
  docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -qx true || die "Container '$CONTAINER' is not running."
  MODE=docker
elif command -v psql >/dev/null 2>&1; then
  MODE=psql
else
  die "Can't reach the database: no running PostgreSQL container was found (try --container NAME), and psql is not installed."
fi

# A value from the environment, else from services/backend/.env, else a default. The file is read, never executed.
setting() {
  local key="$1" default="$2" v="${!1:-}"
  if [[ -z "$v" && -f "$ROOT/services/backend/.env" ]]; then
    v="$(grep -E "^${key}=" "$ROOT/services/backend/.env" | tail -n 1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//')"
  fi
  printf '%s' "${v:-$default}"
}

# Runs SQL read from stdin. Extra arguments (such as -v name=value) go to psql.
sql() {
  if [[ "$MODE" == docker ]]; then
    docker exec -i "$CONTAINER" sh -c 'exec psql -U "${POSTGRES_USER:-nimbus}" -d "${POSTGRES_DB:-nimbus}" -X -q -t -A -v ON_ERROR_STOP=1 "$@"' sh "$@"
  else
    PGPASSWORD="$(setting POSTGRES_PASSWORD '')" psql -w -X -q -t -A -v ON_ERROR_STOP=1 \
      -h "$(setting POSTGRES_HOST localhost)" -p "$(setting POSTGRES_PORT 5432)" \
      -U "$(setting POSTGRES_USER nimbus)" -d "$(setting POSTGRES_DB nimbus)" "$@"
  fi
}

# --- look the person up -----------------------------------------------------------------------------------------------

if ! ROWS="$(sql -v "login=$LOGIN" <<'SQL'
SELECT u.id || '|' || coalesce(u.name, '') || '|' || u.role
FROM users u
JOIN user_identities i ON i.user_id = u.id
WHERE i.provider = 'GITHUB' AND lower(i.login) = lower(:'login');
SQL
)"; then
  die "Could not query the database (see the message above). Is it running, and is the schema created (has the backend started once)?"
fi

COUNT="$(printf '%s' "$ROWS" | grep -c . || true)"
if [[ "$COUNT" -eq 0 ]]; then
  die "No account for GitHub user '$LOGIN'. They need to sign in to Nimbus once first, so that their account exists."
elif [[ "$COUNT" -gt 1 ]]; then
  die "More than one account matches '$LOGIN'. Nothing was changed."
fi

IFS='|' read -r ID NAME ROLE <<< "$ROWS"
WHO="${NAME:-$LOGIN} (@$LOGIN)"

if [[ "$ROLE" == "SUPER_ADMIN" ]]; then
  echo "$WHO is already a super admin. Nothing to do."
  exit 0
fi

# --- promote ----------------------------------------------------------------------------------------------------------

DONE="$(sql -v "id=$ID" <<'SQL'
UPDATE users SET role = 'SUPER_ADMIN', updated_at = now() WHERE id = :'id'::uuid RETURNING 'ok';
SQL
)"
[[ "$DONE" == "ok" ]] || die "The update did not apply. Nothing was changed."

echo "$WHO is now a super admin (was $ROLE)."
echo "It takes effect straight away: they just need to reload Nimbus."
