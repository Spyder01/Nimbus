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

	// What happens once a stopped job has been acknowledged. Mirrors DeploymentLifecycle.settle for a deployment that
	// is being stopped: work that hasn't started is cancelled, and when nothing is left running or waiting the
	// deployment ends and the app's state follows.
	queryEndTask = `
		UPDATE deployment_tasks
		SET state = 'CANCELLED', finished_at = clock_timestamp(), error = COALESCE(error, 'Stopped')
		WHERE id = $1::uuid`

	querySkipWaiting = `
		UPDATE deployment_tasks
		SET state = 'CANCELLED', finished_at = clock_timestamp(), error = COALESCE(error, 'Skipped: the deployment was stopped')
		WHERE deployment_id = $1::uuid AND state IN ('PENDING', 'QUEUED')`

	queryStillActive = `
		SELECT EXISTS (SELECT 1 FROM deployment_tasks WHERE deployment_id = $1::uuid AND state IN ('PENDING', 'QUEUED', 'IN_PROGRESS'))`

	// A deployment that was cancelled ends CANCELLED; one that was abandoned because a container failed ends FAILED.
	queryEndDeployment = `
		UPDATE deployments d
		SET state = CASE WHEN d.cancel_requested_at IS NOT NULL THEN 'CANCELLED' ELSE 'FAILED' END,
		    finished_at = clock_timestamp(),
		    error = CASE
		        WHEN d.cancel_requested_at IS NOT NULL THEN CASE WHEN d.started_at IS NULL THEN 'Cancelled before it started' ELSE 'Cancelled' END
		        ELSE COALESCE(
		            (SELECT 'Container ' || t.name || ' failed' || COALESCE(': ' || t.error, '')
		             FROM deployment_tasks t WHERE t.deployment_id = d.id AND t.state = 'FAILED' ORDER BY t.ordinal LIMIT 1),
		            'The deployment ended before every container was deployed')
		    END
		WHERE d.id = $1::uuid AND d.state IN ('QUEUED', 'IN_PROGRESS')
		RETURNING d.state`

	// A cancelled deployment falls back to what was running before, or to a plain draft if nothing ever succeeded.
	queryAppAfterEnd = `
		UPDATE apps a
		SET state = CASE
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

// AcknowledgeStop records that the worker stopped a job because a heartbeat said to: the task becomes CANCELLED, work
// that hadn't started is cancelled too, and once nothing is left running the deployment (and the app) is settled.
// It returns false, with no error, if the worker no longer holds the job.
func AcknowledgeStop(ctx context.Context, pool *pgxpool.Pool, taskID, workerID string) (bool, error) {
	tx, deploymentID, err := lockForTask(ctx, pool, taskID)
	if err != nil || tx == nil {
		return false, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	holds, err := holdsLease(ctx, tx, taskID, workerID)
	if err != nil || !holds {
		return false, err
	}
	if _, err := tx.Exec(ctx, queryEndTask, taskID); err != nil {
		return false, fmt.Errorf("ending job: %w", err)
	}
	if _, err := tx.Exec(ctx, querySkipWaiting, deploymentID); err != nil {
		return false, fmt.Errorf("cancelling waiting jobs: %w", err)
	}
	var active bool
	if err := tx.QueryRow(ctx, queryStillActive, deploymentID).Scan(&active); err != nil {
		return false, fmt.Errorf("checking for running jobs: %w", err)
	}
	if !active {
		var state string
		err := tx.QueryRow(ctx, queryEndDeployment, deploymentID).Scan(&state)
		switch {
		case errors.Is(err, pgx.ErrNoRows):
			// Already finished by someone else: nothing to settle.
		case err != nil:
			return false, fmt.Errorf("ending deployment: %w", err)
		default:
			var appID string
			if err := tx.QueryRow(ctx, `SELECT app_id::text FROM deployments WHERE id = $1::uuid`, deploymentID).Scan(&appID); err != nil {
				return false, fmt.Errorf("finding app: %w", err)
			}
			if _, err := tx.Exec(ctx, queryAppAfterEnd, appID, state); err != nil {
				return false, fmt.Errorf("updating app state: %w", err)
			}
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("committing stop: %w", err)
	}
	return true, nil
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
