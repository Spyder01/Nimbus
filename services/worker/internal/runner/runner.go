// Package runner does the work of a claimed job. For now that is a stand-in: it waits and succeeds, so a deployment can
// be followed from start to finish (and cancelled) before anything is deployed to Kubernetes. The real runner replaces
// Run and keeps its shape: it works until it is done or ctx is cancelled, and returns an error if the job failed.
package runner

import (
	"context"
	"os"
	"strconv"
	"time"

	"nimbus/worker/internal/jobs"
)

const defaultDuration = 15 * time.Second

// duration is how long the stand-in takes per job. WORKER_FAKE_JOB_SECONDS changes it (0 finishes straight away).
func duration() time.Duration {
	if n, err := strconv.Atoi(os.Getenv("WORKER_FAKE_JOB_SECONDS")); err == nil && n >= 0 {
		return time.Duration(n) * time.Second
	}
	return defaultDuration
}

// Run does the job. It returns nil when the job succeeded, ctx's error if it was stopped, and any other error when it
// failed. Stopping is the caller's decision (a cancel, a lost lease, shutdown): Run only has to notice quickly.
func Run(ctx context.Context, _ jobs.Job) error {
	t := time.NewTimer(duration())
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
