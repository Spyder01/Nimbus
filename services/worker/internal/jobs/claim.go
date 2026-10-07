// Package jobs holds the queries the worker uses on the backend's tables. A job is one deployment task, a row of
// deployment_tasks: one container of a deployment. The backend plans the tasks and owns the schema; this package
// only claims them.
//
// The rules mirror the backend's DeploymentWorkerService.claim, so the two stay interchangeable:
//   - only QUEUED tasks (everything they need has SUCCEEDED) can be claimed, so dependencies always go first;
//   - claiming sets a lease: the expiry is fixed at claim time (never extended) and lease_updated_at is the
//     heartbeat, both from the database's clock so every worker agrees on time;
//   - the first claim of a deployment moves it from QUEUED to IN_PROGRESS.
//
// A worker also holds at most maxHeld jobs at once: it only claims while the number of IN_PROGRESS jobs leased to
// its id is below that. The count comes from the database rather than a counter in the worker, so it can't drift,
// and a slot frees up by itself as soon as a job stops being IN_PROGRESS. The check runs inside the claim
// transaction, after a lock that is private to the worker's id, so goroutines of one worker claiming at the same
// time can't overshoot the limit.
//
// Locks are always taken in the order app, then deployment, then task (the worker's own lock comes before all of
// them, and only workers ever take it). The backend takes them in the same order
// (user cancels, task completions), so a worker and the backend can't deadlock. That is also why the claim isn't a
// single `UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED)`: that would lock the task first.
package jobs

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Job is a claimed task.
type Job struct {
	ID           string
	DeploymentID string
	AppID        string
	// NodeID and Name identify the container in the app's graph.
	NodeID  string
	Name    string
	Layer   int
	Ordinal int
	// Spec is the container's configuration as the backend stored it (image, port, replicas, env, volume, ...).
	// Secret environment variables carry only their key.
	Spec json.RawMessage
	// Attempts counts claims of this task, including this one.
	Attempts int
	// LeaseExpiresAt is the fixed end of the lease. Finish before it, or the task can be taken by another worker.
	LeaseExpiresAt time.Time
}

// ErrNoQuota means the worker already holds as many jobs as it is allowed to. Nothing is wrong: try again when a
// job has finished or the limit has been raised.
var ErrNoQuota = errors.New("no quota left: the worker already holds its maximum number of jobs")

// candidateLimit is how many of the oldest ready tasks ClaimNext tries before giving up.
const candidateLimit = 10

const (
	// Serialises the claims of one worker id for the rest of the transaction (other workers are unaffected).
	queryLockWorker = `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`

	// How many jobs a worker is holding right now.
	queryHeld = `SELECT count(*) FROM deployment_tasks WHERE lease_owner = $1 AND state = 'IN_PROGRESS'`

	// The oldest ready tasks first. Not locked: ClaimTask re-checks everything once it holds the locks.
	queryCandidates = `
		SELECT id::text
		FROM deployment_tasks
		WHERE state = 'QUEUED'
		ORDER BY created_at, ordinal
		LIMIT $1`

	// Which deployment and app a task belongs to (a plain read, so nothing is cached or locked yet).
	queryOwners = `
		SELECT t.deployment_id::text, d.app_id::text
		FROM deployment_tasks t
		JOIN deployments d ON d.id = t.deployment_id
		WHERE t.id = $1::uuid`

	// Locks, in this order: app, deployment, task.
	queryLockApp        = `SELECT id FROM apps WHERE id = $1::uuid FOR UPDATE`
	queryLockDeployment = `SELECT state FROM deployments WHERE id = $1::uuid FOR UPDATE`
	queryLockTask       = `SELECT state FROM deployment_tasks WHERE id = $1::uuid FOR UPDATE`

	// Takes the lease. The current time is read once (clock_timestamp() is the real time; now() would be the start of
	// the transaction, which may be earlier if we had to wait for a lock) so that the lease starts at a single
	// instant: the heartbeat equals the acquire time and the expiry is exactly one lease length later.
	queryClaim = `
		WITH t AS (SELECT clock_timestamp() AS at)
		UPDATE deployment_tasks
		SET state             = 'IN_PROGRESS',
		    attempts          = attempts + 1,
		    lease_owner       = $2,
		    lease_acquired_at = t.at,
		    lease_updated_at  = t.at,
		    lease_expires_at  = t.at + make_interval(secs => $3::double precision),
		    started_at        = COALESCE(started_at, t.at)
		FROM t
		WHERE id = $1::uuid
		RETURNING id::text, deployment_id::text, node_id, name, layer, ordinal, spec, attempts, lease_expires_at`

	// The first claimed task starts the deployment.
	queryStartDeployment = `
		UPDATE deployments
		SET state = 'IN_PROGRESS', started_at = COALESCE(started_at, clock_timestamp())
		WHERE id = $1::uuid`

	// An app with a deployment under way is DEPLOYING (already the case unless something changed it).
	queryMarkAppDeploying = `
		UPDATE apps SET state = 'DEPLOYING', updated_at = clock_timestamp()
		WHERE id = $1::uuid AND state <> 'DEPLOYING'`
)

// ClaimNext claims the oldest ready job for workerID, giving it a lease of the given length, as long as the worker
// holds fewer than maxHeld jobs; otherwise it returns ErrNoQuota. It returns false when there is nothing to claim.
// Another worker winning a job in the meantime isn't an error: the next one is tried.
func ClaimNext(ctx context.Context, pool *pgxpool.Pool, workerID string, lease time.Duration, maxHeld int) (Job, bool, error) {
	if err := validate(workerID, lease, maxHeld); err != nil {
		return Job{}, false, err
	}

	// A cheap early answer when the worker is full, so the candidate list isn't fetched for nothing. ClaimTask
	// checks again under the lock, which is what actually enforces the limit.
	held, err := held(ctx, pool, workerID)
	if err != nil {
		return Job{}, false, err
	}
	if held >= maxHeld {
		return Job{}, false, ErrNoQuota
	}

	rows, err := pool.Query(ctx, queryCandidates, candidateLimit)
	if err != nil {
		return Job{}, false, fmt.Errorf("finding ready jobs: %w", err)
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return Job{}, false, fmt.Errorf("finding ready jobs: %w", err)
	}

	for _, id := range ids {
		job, ok, err := ClaimTask(ctx, pool, id, workerID, lease, maxHeld)
		if err != nil {
			return Job{}, false, err // including ErrNoQuota, if the worker filled up in the meantime
		}
		if ok {
			return job, true, nil
		}
	}
	return Job{}, false, nil
}

// ClaimTask claims one specific job, as long as the worker holds fewer than maxHeld jobs (otherwise ErrNoQuota).
// It returns false, with no error, if the job can't be claimed any more: it is not ready yet, someone else holds
// it, or it was cancelled or finished.
func ClaimTask(ctx context.Context, pool *pgxpool.Pool, taskID, workerID string, lease time.Duration, maxHeld int) (Job, bool, error) {
	if err := validate(workerID, lease, maxHeld); err != nil {
		return Job{}, false, err
	}

	tx, err := pool.Begin(ctx)
	if err != nil {
		return Job{}, false, fmt.Errorf("starting claim transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }() // does nothing once committed

	// The quota. Other claims by this worker wait here, so the count below is still true when we take the lease.
	var ignored string
	if err := tx.QueryRow(ctx, queryLockWorker, workerID).Scan(&ignored); err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return Job{}, false, fmt.Errorf("locking worker: %w", err)
	}
	var heldNow int
	if err := tx.QueryRow(ctx, queryHeld, workerID).Scan(&heldNow); err != nil {
		return Job{}, false, fmt.Errorf("counting held jobs: %w", err)
	}
	if heldNow >= maxHeld {
		return Job{}, false, ErrNoQuota
	}

	var deploymentID, appID string
	if err := tx.QueryRow(ctx, queryOwners, taskID).Scan(&deploymentID, &appID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return Job{}, false, nil
		}
		return Job{}, false, fmt.Errorf("looking up job %s: %w", taskID, err)
	}

	// Take the locks, then look again: the state we saw while choosing the job may have changed since.
	var locked string
	if err := tx.QueryRow(ctx, queryLockApp, appID).Scan(&locked); err != nil {
		return Job{}, false, fmt.Errorf("locking app: %w", err)
	}
	var deploymentState string
	if err := tx.QueryRow(ctx, queryLockDeployment, deploymentID).Scan(&deploymentState); err != nil {
		return Job{}, false, fmt.Errorf("locking deployment: %w", err)
	}
	var taskState string
	if err := tx.QueryRow(ctx, queryLockTask, taskID).Scan(&taskState); err != nil {
		return Job{}, false, fmt.Errorf("locking job: %w", err)
	}
	if taskState != "QUEUED" || (deploymentState != "QUEUED" && deploymentState != "IN_PROGRESS") {
		return Job{}, false, nil
	}

	var job Job
	var spec []byte
	if err := tx.QueryRow(ctx, queryClaim, taskID, workerID, lease.Seconds()).Scan(
		&job.ID, &job.DeploymentID, &job.NodeID, &job.Name, &job.Layer, &job.Ordinal, &spec, &job.Attempts, &job.LeaseExpiresAt,
	); err != nil {
		return Job{}, false, fmt.Errorf("claiming job: %w", err)
	}
	job.AppID = appID
	job.Spec = json.RawMessage(spec)

	if deploymentState == "QUEUED" {
		if _, err := tx.Exec(ctx, queryStartDeployment, deploymentID); err != nil {
			return Job{}, false, fmt.Errorf("starting deployment: %w", err)
		}
		if _, err := tx.Exec(ctx, queryMarkAppDeploying, appID); err != nil {
			return Job{}, false, fmt.Errorf("updating app state: %w", err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return Job{}, false, fmt.Errorf("committing claim: %w", err)
	}
	return job, true, nil
}

// held is the number of jobs workerID is holding (IN_PROGRESS and leased to it).
func held(ctx context.Context, pool *pgxpool.Pool, workerID string) (int, error) {
	var n int
	if err := pool.QueryRow(ctx, queryHeld, workerID).Scan(&n); err != nil {
		return 0, fmt.Errorf("counting held jobs: %w", err)
	}
	return n, nil
}

func validate(workerID string, lease time.Duration, maxHeld int) error {
	if workerID == "" {
		return errors.New("workerID is required")
	}
	if maxHeld <= 0 {
		return fmt.Errorf("maxHeld must be positive, got %d", maxHeld)
	}
	if lease <= 0 {
		return fmt.Errorf("lease must be positive, got %s", lease)
	}
	return nil
}
