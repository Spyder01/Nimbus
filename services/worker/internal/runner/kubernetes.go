package runner

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"nimbus/worker/internal/jobs"
	"nimbus/worker/internal/kube"
)

// Kubernetes deploys a job's container to a cluster and waits until it is ready.
type Kubernetes struct {
	deployer *kube.Deployer
	timeout  time.Duration
	// Where public containers get their addresses. Zero means they can't be deployed.
	expose kube.Expose
}

// How long the Gateway gets to accept a container's route once the container itself is ready.
const routeTimeout = 60 * time.Second

func (k *Kubernetes) Run(ctx context.Context, job jobs.Job) (Result, error) {
	log := slog.With("job_id", job.ID, "name", job.Name, "app_id", job.AppID)

	c, err := kube.ParseContainer(job.Spec)
	if err != nil {
		return Result{}, err
	}
	// Fail before touching the cluster if a public address can't be given.
	var url string
	if c.Expose {
		if !k.expose.Configured() {
			return Result{}, errors.New("the container is public, but this worker has no WORKER_BASE_DOMAIN and WORKER_GATEWAY to give it an address")
		}
		if c.Port == nil {
			return Result{}, errors.New("a public container needs a port")
		}
		if url, err = k.expose.URL(job.AppID, c.Name); err != nil {
			return Result{}, err
		}
	}

	created, err := k.deployer.Apply(ctx, job.AppID, job.DeploymentID, c, int32(k.timeout.Seconds()))
	if err != nil {
		return Result{}, k.stopped(ctx, log, job, created, err)
	}
	if c.Expose {
		err = k.deployer.ApplyRoute(ctx, job.AppID, job.DeploymentID, c, k.expose)
	} else {
		// No longer (or never) public: make sure an earlier deployment's route doesn't keep it reachable.
		err = k.deployer.DeleteRoute(ctx, job.AppID, c.Name)
	}
	if err != nil {
		return Result{}, k.stopped(ctx, log, job, created, err)
	}
	log.Info("applied to the cluster; waiting until it is ready", "namespace", kube.NamespaceFor(job.AppID), "new", created, "public", c.Expose)

	if err := k.deployer.WaitReady(ctx, job.AppID, c.Name, k.timeout); err != nil {
		return Result{}, k.stopped(ctx, log, job, created, err)
	}
	if c.Expose {
		if err := k.deployer.WaitRouteAccepted(ctx, job.AppID, c.Name, routeTimeout); err != nil {
			return Result{}, k.stopped(ctx, log, job, created, err)
		}
		log.Info("the container is ready and public", "url", url)
		return Result{URL: url}, nil
	}
	log.Info("the container is ready")
	return Result{}, nil
}

// stopped finishes a run that ended with err. When a user cancelled the deployment and this run was the one that
// created the container, what it made is removed again, so a cancelled first deploy leaves nothing behind. A container
// that was already there is left as it is, and so is everything when the stop was a lost lease or a shutdown.
func (k *Kubernetes) stopped(ctx context.Context, log *slog.Logger, job jobs.Job, created bool, err error) error {
	if ctx.Err() == nil || !errors.Is(context.Cause(ctx), ErrStopped) || !created {
		return err
	}
	// ctx is cancelled, so the cleanup needs a context of its own.
	cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
	defer cancel()
	if rmErr := k.deployer.Remove(cleanup, job.AppID, job.Name); rmErr != nil {
		log.Error("could not remove what the cancelled deployment created", "error", rmErr)
		return fmt.Errorf("%w (and cleaning up failed: %v)", err, rmErr)
	}
	log.Info("removed what the cancelled deployment had created")
	return err
}
