package registry

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Settings are the stored settings that apply to one worker, resolved across its two levels: the worker's own
// override, else its pool's default. A nil value means nothing is stored for it, so the worker's own default
// (its environment) applies.
type Settings struct {
	ParallelJobs *int
	LeaseSeconds *int
	// Version is the highest version of the rows that were read, or 0 if there are none. It rises with every change
	// to either row, so a worker can tell that something changed by comparing it with the last one it applied.
	Version int64
}

const (
	querySettings = `
		SELECT scope, parallel_jobs, lease_seconds, version
		FROM worker_settings
		WHERE (scope = 'POOL' AND name = $1) OR (scope = 'WORKER' AND name = $2)`

	// The worker records what it has applied, only while it still holds the slot. Success clears any earlier problem;
	// a problem keeps the last version it did apply, so the UI can show "pending" next to the reason.
	queryReportApplied = `
		UPDATE worker_slots
		SET applied_settings_version = $3, settings_error = NULL
		WHERE name = $1 AND instance_id = $2`

	queryReportProblem = `
		UPDATE worker_slots
		SET settings_error = $3
		WHERE name = $1 AND instance_id = $2`
)

// ReadSettings returns the settings stored for the worker called name in pool poolName.
func ReadSettings(ctx context.Context, pool *pgxpool.Pool, poolName, name string) (Settings, error) {
	rows, err := pool.Query(ctx, querySettings, poolName, name)
	if err != nil {
		return Settings{}, fmt.Errorf("reading settings: %w", err)
	}
	defer rows.Close()

	var own, shared Settings
	for rows.Next() {
		var scope string
		var jobs, lease *int
		var version int64
		if err := rows.Scan(&scope, &jobs, &lease, &version); err != nil {
			return Settings{}, fmt.Errorf("reading settings: %w", err)
		}
		row := Settings{ParallelJobs: jobs, LeaseSeconds: lease, Version: version}
		if scope == "WORKER" {
			own = row
		} else {
			shared = row
		}
	}
	if err := rows.Err(); err != nil {
		return Settings{}, fmt.Errorf("reading settings: %w", err)
	}

	out := Settings{ParallelJobs: own.ParallelJobs, LeaseSeconds: own.LeaseSeconds, Version: max(own.Version, shared.Version)}
	if out.ParallelJobs == nil {
		out.ParallelJobs = shared.ParallelJobs
	}
	if out.LeaseSeconds == nil {
		out.LeaseSeconds = shared.LeaseSeconds
	}
	return out, nil
}

// ReportApplied records that the worker has applied settings up to version, clearing any earlier problem.
func ReportApplied(ctx context.Context, pool *pgxpool.Pool, name, instanceID string, version int64) error {
	if _, err := pool.Exec(ctx, queryReportApplied, name, instanceID, version); err != nil {
		return fmt.Errorf("reporting applied settings: %w", err)
	}
	return nil
}

// ReportProblem records why the worker could not apply the stored settings. The version it last applied stays.
func ReportProblem(ctx context.Context, pool *pgxpool.Pool, name, instanceID string, problem error) error {
	if problem == nil {
		return errors.New("no problem to report")
	}
	if _, err := pool.Exec(ctx, queryReportProblem, name, instanceID, problem.Error()); err != nil {
		return fmt.Errorf("reporting a settings problem: %w", err)
	}
	return nil
}
