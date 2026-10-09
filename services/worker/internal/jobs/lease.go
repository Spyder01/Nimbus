package jobs

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Beat is the answer to a heartbeat.
type Beat struct {
	// HoldsLease is false when the job is no longer ours: the lease ran out, or the backend gave the job to someone
	// else or ended it. The worker must stop working on it and must not report anything about it.
	HoldsLease bool
	// StopRequested is true when the deployment was cancelled (by the user, or because another container failed).
	// The worker should stop the job and call AcknowledgeStop.
	StopRequested bool
}

const (
	// The lease holder test is the backend's (DeploymentWorkerService.holds): still IN_PROGRESS, leased to this
	// worker, and before the fixed expiry.
	queryHolds = `
		SELECT state = 'IN_PROGRESS' AND lease_owner = $2 AND clock_timestamp() < lease_expires_at
		FROM deployment_tasks WHERE id = $1::uuid`

	// Only lease_updated_at moves; the expiry is fixed when the job is claimed and never extended.
	queryTouch = `UPDATE deployment_tasks SET lease_updated_at = clock_timestamp() WHERE id = $1::uuid`

	queryStopRequested = `SELECT cancel_requested_at IS NOT NULL OR abort_requested_at IS NOT NULL FROM deployments WHERE id = $1::uuid`

	// Ending a job. Together these mirror the backend's DeploymentLifecycle.settle:
	//   - while the deployment is being stopped (cancelled, or abandoned because a container failed), work that hasn't
	//     started is cancelled; otherwise work whose dependencies have all succeeded becomes ready;
	//   - once nothing is running or waiting, the deployment ends and the app's state follows.
	queryEndTask = `
		UPDATE deployment_tasks
		SET state = $2, finished_at = clock_timestamp(), error = COALESCE($3, error), url = $4
		WHERE id = $1::uuid`

	queryAbort = `UPDATE deployments SET abort_requested_at = clock_timestamp() WHERE id = $1::uuid AND abort_requested_at IS NULL`

	// The deployment, if it is still being handled (otherwise there is nothing to settle), and whether it is stopping.
	queryDeploymentForSettle = `
		SELECT cancel_requested_at IS NOT NULL OR abort_requested_at IS NOT NULL
		FROM deployments WHERE id = $1::uuid AND state IN ('QUEUED', 'IN_PROGRESS')`

	querySkipWaiting = `
		UPDATE deployment_tasks
		SET state = 'CANCELLED', finished_at = clock_timestamp(), error = COALESCE(error, 'Skipped: the deployment was stopped')
		WHERE deployment_id = $1::uuid AND state IN ('PENDING', 'QUEUED')`

	queryPromoteReady = `
		UPDATE deployment_tasks t
		SET state = 'QUEUED'
		WHERE t.deployment_id = $1::uuid AND t.state = 'PENDING'
		  AND NOT EXISTS (
		      SELECT 1 FROM unnest(t.depends_on) AS needed(id)
		      WHERE NOT EXISTS (SELECT 1 FROM deployment_tasks d WHERE d.id = needed.id AND d.state = 'SUCCEEDED'))`

	queryStillActive = `
		SELECT EXISTS (SELECT 1 FROM deployment_tasks WHERE deployment_id = $1::uuid AND state IN ('PENDING', 'QUEUED', 'IN_PROGRESS'))`

	// A cancelled deployment ends CANCELLED; else one with a failed container ends FAILED; else SUCCEEDED when every
	// container did; anything else ended early and FAILED.
	queryEndDeployment = `
		UPDATE deployments d
		SET state = CASE
		        WHEN d.cancel_requested_at IS NOT NULL THEN 'CANCELLED'
		        WHEN EXISTS (SELECT 1 FROM deployment_tasks t WHERE t.deployment_id = d.id AND t.state = 'FAILED') THEN 'FAILED'
		        WHEN NOT EXISTS (SELECT 1 FROM deployment_tasks t WHERE t.deployment_id = d.id AND t.state <> 'SUCCEEDED') THEN 'SUCCEEDED'
		        ELSE 'FAILED'
		    END,
		    finished_at = clock_timestamp(),
		    error = CASE
		        WHEN d.cancel_requested_at IS NOT NULL THEN CASE WHEN d.started_at IS NULL THEN 'Cancelled before it started' ELSE 'Cancelled' END
		        WHEN EXISTS (SELECT 1 FROM deployment_tasks t WHERE t.deployment_id = d.id AND t.state = 'FAILED') THEN
		            (SELECT 'Container ' || t.name || ' failed' || COALESCE(': ' || t.error, '')
		             FROM deployment_tasks t WHERE t.deployment_id = d.id AND t.state = 'FAILED' ORDER BY t.ordinal LIMIT 1)
		        WHEN NOT EXISTS (SELECT 1 FROM deployment_tasks t WHERE t.deployment_id = d.id AND t.state <> 'SUCCEEDED') THEN d.error
		        ELSE 'The deployment ended before every container was deployed'
		    END
		WHERE d.id = $1::uuid
		RETURNING d.state, d.app_id::text`

	// A cancelled deployment falls back to what was running before, or to a plain draft if nothing ever succeeded.
	queryAppAfterEnd = `
		UPDATE apps a
		SET state = CASE
		        WHEN $2 = 'SUCCEEDED' THEN 'RUNNING'
		        WHEN $2 = 'FAILED' THEN 'FAILED'
		        WHEN EXISTS (SELECT 1 FROM deployments s WHERE s.app_id = a.id AND s.state = 'SUCCEEDED') THEN 'RUNNING'
		        ELSE 'DRAFT'
		    END,
		    updated_at = clock_timestamp()
		WHERE a.id = $1::uuid`
)

// Heartbeat renews the job's lease (lease_updated_at; the expiry stays where it was) and reports whether the worker
// still holds it and whether it should stop. Call it regularly while the job runs: the backend treats a job whose
// heartbeat has gone quiet as abandoned and takes it back.
func Heartbeat(ctx context.Context, pool *pgxpool.Pool, taskID, workerID string) (Beat, error) {
	tx, deploymentID, err := lockForTask(ctx, pool, taskID)
	if err != nil || tx == nil {
		return Beat{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	holds, err := holdsLease(ctx, tx, taskID, workerID)
	if err != nil || !holds {
		return Beat{}, err
	}
	if _, err := tx.Exec(ctx, queryTouch, taskID); err != nil {
		return Beat{}, fmt.Errorf("renewing lease: %w", err)
	}
	var stop bool
	if err := tx.QueryRow(ctx, queryStopRequested, deploymentID).Scan(&stop); err != nil {
		return Beat{}, fmt.Errorf("reading deployment state: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return Beat{}, fmt.Errorf("committing heartbeat: %w", err)
	}
	return Beat{HoldsLease: true, StopRequested: stop}, nil
}

// Complete records that the worker finished the job. Containers that were waiting for it become ready, and when it was
// the last one the deployment succeeds and the app is RUNNING. url is where the container can be opened from outside
// the cluster ("" if it isn't public). It returns false, with no error, if the worker no longer holds the job (its
// lease ran out, or it was taken back): then nothing is changed.
func Complete(ctx context.Context, pool *pgxpool.Pool, taskID, workerID, url string) (bool, error) {
	return finish(ctx, pool, taskID, workerID, "SUCCEEDED", nil, false, url)
}

// Fail records that the job failed for good. The rest of the deployment is abandoned: containers that hadn't started
// are cancelled, running ones are told to stop at their next heartbeat, and the deployment ends FAILED once nothing is
// running. Returns false if the worker no longer holds the job.
func Fail(ctx context.Context, pool *pgxpool.Pool, taskID, workerID, reason string) (bool, error) {
	return finish(ctx, pool, taskID, workerID, "FAILED", &reason, true, "")
}

// AcknowledgeStop records that the worker stopped the job because a heartbeat said to: the task becomes CANCELLED, work
// that hadn't started is cancelled too, and once nothing is left running the deployment (and the app) is settled.
// Returns false if the worker no longer holds the job.
func AcknowledgeStop(ctx context.Context, pool *pgxpool.Pool, taskID, workerID string) (bool, error) {
	reason := "Stopped"
	return finish(ctx, pool, taskID, workerID, "CANCELLED", &reason, false, "")
}

func finish(ctx context.Context, pool *pgxpool.Pool, taskID, workerID, state string, reason *string, abort bool, url string) (bool, error) {
	tx, deploymentID, err := lockForTask(ctx, pool, taskID)
	if err != nil || tx == nil {
		return false, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	holds, err := holdsLease(ctx, tx, taskID, workerID)
	if err != nil || !holds {
		return false, err
	}
	var publicURL *string // null unless there is one
	if url != "" {
		publicURL = &url
	}
	if _, err := tx.Exec(ctx, queryEndTask, taskID, state, reason, publicURL); err != nil {
		return false, fmt.Errorf("ending job: %w", err)
	}
	if abort {
		if _, err := tx.Exec(ctx, queryAbort, deploymentID); err != nil {
			return false, fmt.Errorf("abandoning deployment: %w", err)
		}
	}
	if err := settle(ctx, tx, deploymentID); err != nil {
		return false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("committing: %w", err)
	}
	return true, nil
}

// settle brings the deployment's other jobs, the deployment and the app up to date after a job ended.
func settle(ctx context.Context, tx pgx.Tx, deploymentID string) error {
	var stopping bool
	if err := tx.QueryRow(ctx, queryDeploymentForSettle, deploymentID).Scan(&stopping); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil // already finished by someone else
		}
		return fmt.Errorf("reading deployment: %w", err)
	}
	next := queryPromoteReady
	if stopping {
		next = querySkipWaiting
	}
	if _, err := tx.Exec(ctx, next, deploymentID); err != nil {
		return fmt.Errorf("updating waiting jobs: %w", err)
	}

	var active bool
	if err := tx.QueryRow(ctx, queryStillActive, deploymentID).Scan(&active); err != nil {
		return fmt.Errorf("checking for running jobs: %w", err)
	}
	if active {
		return nil
	}
	var state, appID string
	if err := tx.QueryRow(ctx, queryEndDeployment, deploymentID).Scan(&state, &appID); err != nil {
		return fmt.Errorf("ending deployment: %w", err)
	}
	if _, err := tx.Exec(ctx, queryAppAfterEnd, appID, state); err != nil {
		return fmt.Errorf("updating app state: %w", err)
	}
	return nil
}

// lockForTask starts a transaction holding the locks in the usual order (app, deployment, task). A nil transaction
// with a nil error means the job no longer exists.
func lockForTask(ctx context.Context, pool *pgxpool.Pool, taskID string) (pgx.Tx, string, error) {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return nil, "", fmt.Errorf("starting transaction: %w", err)
	}
	var deploymentID, appID string
	if err := tx.QueryRow(ctx, queryOwners, taskID).Scan(&deploymentID, &appID); err != nil {
		_ = tx.Rollback(ctx)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, "", nil
		}
		return nil, "", fmt.Errorf("looking up job %s: %w", taskID, err)
	}
	var ignored string
	for _, q := range []struct{ sql, id, what string }{
		{queryLockApp, appID, "app"}, {queryLockDeployment, deploymentID, "deployment"}, {queryLockTask, taskID, "job"},
	} {
		if err := tx.QueryRow(ctx, q.sql, q.id).Scan(&ignored); err != nil {
			_ = tx.Rollback(ctx)
			return nil, "", fmt.Errorf("locking %s: %w", q.what, err)
		}
	}
	return tx, deploymentID, nil
}

func holdsLease(ctx context.Context, tx pgx.Tx, taskID, workerID string) (bool, error) {
	var holds bool
	if err := tx.QueryRow(ctx, queryHolds, taskID, workerID).Scan(&holds); err != nil {
		return false, fmt.Errorf("checking lease: %w", err)
	}
	return holds, nil
}
