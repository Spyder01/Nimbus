// Package runner does the work of a claimed job: deploying one container. Which implementation runs is chosen by
// WORKER_RUNNER: "kubernetes" deploys to a cluster, "fake" (the default) only waits and succeeds, so the rest of the
// system can be tried without a cluster.
//
// A runner works until it is done or ctx is cancelled, and returns an error if the job failed. Stopping is decided by
// the caller (a cancel, a lost lease, shutdown): context.Cause(ctx) says which, see ErrStopped.
package runner

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"time"

	"nimbus/worker/internal/jobs"
	"nimbus/worker/internal/kube"
)

// Result is what a finished job has to report.
type Result struct {
	// URL is where the container can be opened from outside the cluster; empty if it isn't public.
	URL string
}

// Runner deploys one job's container.
type Runner interface {
	Run(ctx context.Context, job jobs.Job) (Result, error)
}

// ErrStopped is the cause of a job's context when the deployment was cancelled by a user (or abandoned because another
// container failed). It is the one kind of stop where a runner may undo what it started: with a lost lease or a
// shutdown, someone else may be deploying the same container right now.
var ErrStopped = errors.New("the deployment was cancelled")

const (
	defaultFakeDuration  = 15 * time.Second
	defaultDeployTimeout = 5 * time.Minute
	minDeployTimeoutSecs = 30
	maxDeployTimeoutSecs = 3600
)

// FromEnv builds the runner chosen by WORKER_RUNNER and describes it for the log.
func FromEnv() (Runner, string, error) {
	switch kind := os.Getenv("WORKER_RUNNER"); kind {
	case "", "fake":
		return Fake{After: fakeDuration()}, "fake (waits, then succeeds; deploys nothing)", nil
	case "kubernetes":
		timeout, err := deployTimeout()
		if err != nil {
			return nil, "", err
		}
		expose, err := kube.NewExpose(os.Getenv("WORKER_BASE_DOMAIN"), os.Getenv("WORKER_GATEWAY"))
		if err != nil {
			return nil, "", err
		}
		cs, where, err := kube.Connect()
		if err != nil {
			return nil, "", err
		}
		desc := fmt.Sprintf("kubernetes: %s", where)
		if expose.Configured() {
			desc += fmt.Sprintf("; public containers at *.%s via gateway %s/%s", expose.BaseDomain, expose.GatewayNamespace, expose.GatewayName)
		} else {
			desc += "; public containers are off (set WORKER_BASE_DOMAIN and WORKER_GATEWAY)"
		}
		return &Kubernetes{deployer: kube.NewDeployer(cs.Core, cs.Dynamic), timeout: timeout, expose: expose}, desc, nil
	default:
		return nil, "", fmt.Errorf("WORKER_RUNNER must be \"kubernetes\" or \"fake\", got %q", kind)
	}
}

// How long a container gets to become ready. WORKER_DEPLOY_TIMEOUT_SECONDS changes it.
func deployTimeout() (time.Duration, error) {
	v := os.Getenv("WORKER_DEPLOY_TIMEOUT_SECONDS")
	if v == "" {
		return defaultDeployTimeout, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < minDeployTimeoutSecs || n > maxDeployTimeoutSecs {
		return 0, fmt.Errorf("WORKER_DEPLOY_TIMEOUT_SECONDS must be between %d and %d, got %q", minDeployTimeoutSecs, maxDeployTimeoutSecs, v)
	}
	return time.Duration(n) * time.Second, nil
}

// Fake is the stand-in runner: it waits, then succeeds.
type Fake struct{ After time.Duration }

// fakeDuration is how long the stand-in takes per job. WORKER_FAKE_JOB_SECONDS changes it (0 finishes straight away).
func fakeDuration() time.Duration {
	if n, err := strconv.Atoi(os.Getenv("WORKER_FAKE_JOB_SECONDS")); err == nil && n >= 0 {
		return time.Duration(n) * time.Second
	}
	return defaultFakeDuration
}

func (f Fake) Run(ctx context.Context, _ jobs.Job) (Result, error) {
	t := time.NewTimer(f.After)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return Result{}, ctx.Err()
	case <-t.C:
		return Result{}, nil
	}
}
