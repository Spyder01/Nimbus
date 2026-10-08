package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	actuator "github.com/sinhashubham95/go-actuator"

	"nimbus/worker/internal/api"
	"nimbus/worker/internal/config"
	"nimbus/worker/internal/db"
	"nimbus/worker/internal/jobs"
	"nimbus/worker/internal/membership"
	"nimbus/worker/internal/runner"
)

const (
	defaultHTTPPort = 8081
	pollInterval    = 5 * time.Second

	// How often a held job's lease is renewed. The backend gives up on a job whose heartbeat is older than its
	// heartbeat-timeout (2 minutes by default), so this leaves room for several missed beats.
	heartbeatInterval = 30 * time.Second
)

// listenPort is where the worker serves /health, /config and /actuator. WORKER_HTTP_PORT changes it, so several workers
// can share a machine.
func listenPort() (int, error) {
	v := os.Getenv("WORKER_HTTP_PORT")
	if v == "" {
		return defaultHTTPPort, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 1 || n > 65535 {
		return 0, fmt.Errorf("WORKER_HTTP_PORT must be a port number between 1 and 65535, got %q", v)
	}
	return n, nil
}

// Shown by /actuator/info. Set at build time with: go build -ldflags "-X main.version=1.2.3"
var version = "dev"

func main() {
	cfg, err := config.Load()
	if err != nil {
		slog.Error("invalid configuration", "error", err)
		os.Exit(1)
	}
	slog.SetDefault(slog.With("worker_id", cfg.WorkerID)) // every log line carries this worker's id
	slog.Info("config loaded", "db_host", cfg.DB.Host, "db_port", cfg.DB.Port, "db_name", cfg.DB.Name, "db_user", cfg.DB.User,
		"parallel_jobs", config.ParallelJobs(), "lease_seconds", int(config.LeaseDuration().Seconds()))

	// Cancelled on Ctrl-C / SIGTERM, or when either goroutine below exits, so they always stop together.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	ctx, cancel := context.WithCancel(ctx)
	defer stop()
	defer cancel()

	pool, err := db.Connect(ctx)
	if err != nil {
		slog.Error("database connection failed", "error", err)
		os.Exit(1)
	}
	defer pool.Close()
	slog.Info("connected to postgres", "db_host", cfg.DB.Host, "db_name", cfg.DB.Name)

	// Take a worker name (a slot in the pool) and apply the settings stored for it.
	host, _ := os.Hostname()
	member, err := membership.Join(ctx, pool, membership.Options{
		Pool: cfg.Pool, Name: cfg.Name, InstanceID: cfg.WorkerID, Version: version, Host: host,
	})
	if err != nil {
		slog.Error("could not register the worker", "error", err)
		pool.Close() // os.Exit skips deferred calls
		os.Exit(1)
	}
	slog.Info("worker ready", "worker_name", member.Name(), "pool", cfg.Pool,
		"parallel_jobs", config.ParallelJobs(), "lease_seconds", int(config.LeaseDuration().Seconds()))

	var wg sync.WaitGroup
	var httpErr error // written by the goroutine below, read only after wg.Wait()
	wg.Add(3)

	// 1. HTTP server with the liveness check, the config endpoint and the actuator endpoints.
	go func() {
		defer wg.Done()
		defer cancel()
		httpErr = serveHTTP(ctx, pool)
	}()

	// 2. Worker loop.
	go func() {
		defer wg.Done()
		defer cancel()
		runWorker(ctx, pool, member)
	}()

	// 3. Keeps the worker's slot alive and its settings current. Separate from the loop above so a slow claim can't
	// make the heartbeat late.
	go func() {
		defer wg.Done()
		defer cancel()
		member.Run(ctx)
	}()

	wg.Wait()
	member.Leave() // free the slot now, so a replacement doesn't have to wait out the lease

	if httpErr != nil {
		pool.Close() // os.Exit skips deferred calls
		os.Exit(1)   // couldn't serve (e.g. the port is taken): fail visibly instead of exiting 0
	}
}

func serveHTTP(ctx context.Context, pool *pgxpool.Pool) error {
	httpPort, err := listenPort()
	if err != nil {
		return err
	}
	addr := fmt.Sprintf(":%d", httpPort)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("PUT /config", api.PutConfig)
	mux.Handle("/actuator/", actuator.GetActuatorHandler(&actuator.Config{
		// /env (dumps every environment variable, including the database password), /shutdown (stops the
		// process) and /threadDump are deliberately left out: nothing here protects them.
		Endpoints: []int{actuator.Info, actuator.Health, actuator.Metrics, actuator.Ping},
		Name:      "nimbus-worker",
		Version:   version,
		Port:      httpPort,
		Health: &actuator.HealthConfig{
			// A successful check is cached; the default is an hour, which would hide a database outage.
			CacheDuration: 5 * time.Second,
			Timeout:       3 * time.Second,
			Checkers: []actuator.HealthChecker{
				{Key: "postgres", IsMandatory: true, Func: pool.Ping},
			},
		},
	}))
	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}

	// When ctx is cancelled, stop accepting requests and let in-flight ones finish.
	context.AfterFunc(ctx, func() {
		shutdownCtx, done := context.WithTimeout(context.Background(), 5*time.Second)
		defer done()
		_ = srv.Shutdown(shutdownCtx)
	})

	slog.Info("http server listening", "addr", addr)
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		slog.Error("http server stopped", "error", err)
		return err
	}
	slog.Info("http server stopped")
	return nil
}

func runWorker(ctx context.Context, pool *pgxpool.Pool, member *membership.Membership) {
	workerID := config.Get().WorkerID
	slog.Info("worker loop started", "interval", pollInterval)
	var held sync.WaitGroup
	defer held.Wait() // on shutdown, let each held job notice and stop
	ticker := time.NewTicker(pollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			slog.Info("worker loop stopped")
			return
		case <-ticker.C:
			// Read each time, so a PUT /config takes effect.
			slog.Info("worker loop tick", "worker_name", member.Name(), "parallel_jobs", config.ParallelJobs(), "lease_seconds", int(config.LeaseDuration().Seconds()))
			if !member.Registered() {
				slog.Warn("not claiming: the worker is not registered right now")
				continue
			}
			claimJob(ctx, pool, workerID, &held)
		}
	}
}

// claimJob tries to claim the oldest ready job. A failure is logged and the loop carries on: the database may
// be briefly unreachable, and the next tick tries again.
func claimJob(ctx context.Context, pool *pgxpool.Pool, workerID string, held *sync.WaitGroup) {
	// Both settings are read now, so a PUT /config applies from the next claim.
	job, ok, err := jobs.ClaimNext(ctx, pool, workerID, config.LeaseDuration(), config.ParallelJobs())
	switch {
	case errors.Is(err, jobs.ErrNoQuota):
		slog.Info("not claiming: all parallel job slots are in use", "parallel_jobs", config.ParallelJobs())
	case err != nil:
		if ctx.Err() == nil { // an error while shutting down is expected
			slog.Error("claiming a job failed", "error", err)
		}
	case ok:
		slog.Info("claimed job",
			"job_id", job.ID, "name", job.Name, "deployment_id", job.DeploymentID, "app_id", job.AppID,
			"attempt", job.Attempts, "lease_expires_at", job.LeaseExpiresAt.Format(time.RFC3339))
		held.Add(1)
		go func() {
			defer held.Done()
			holdJob(ctx, pool, workerID, job)
		}()
	}
}

// holdJob runs a claimed job and keeps its lease alive until it ends. The job runs under jobCtx while this renews the
// lease every heartbeatInterval. It ends in one of these ways:
//   - the job finishes: reported as completed, or failed with its error;
//   - the deployment is cancelled: jobCtx is cancelled so the job stops, and that is acknowledged;
//   - the lease is lost or runs out: the job is stopped and nothing is reported (it isn't ours any more);
//   - the worker shuts down: the job is stopped and the lease is left for the backend to take back.
func holdJob(ctx context.Context, pool *pgxpool.Pool, workerID string, job jobs.Job) {
	log := slog.With("job_id", job.ID, "name", job.Name, "deployment_id", job.DeploymentID)
	jobCtx, stopJob := context.WithDeadline(ctx, job.LeaseExpiresAt) // the lease can't be renewed past its fixed expiry
	defer stopJob()

	finished := make(chan error, 1)
	go func() { finished <- runner.Run(jobCtx, job) }()
	ran := false
	defer func() {
		if !ran { // make sure the job has really stopped before this returns
			stopJob()
			<-finished
		}
	}()

	// Reports run on their own context: jobCtx may already be cancelled by the time we need to report.
	report := func(f func(context.Context) (bool, error), what string) {
		rctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cancel()
		ok, err := f(rctx)
		switch {
		case err != nil:
			log.Error("could not record that the job "+what+"; the backend will take it back", "error", err)
		case !ok:
			log.Warn("the job " + what + ", but it was no longer ours to report")
		default:
			log.Info("the job " + what)
		}
	}

	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case err := <-finished:
			ran = true
			switch {
			case err == nil:
				report(func(c context.Context) (bool, error) { return jobs.Complete(c, pool, job.ID, workerID) }, "finished")
			case jobCtx.Err() != nil:
				// It was stopped from outside (lease ran out, or shutdown): the job isn't ours to report on.
				log.Warn("the job was stopped before it finished")
			default:
				log.Error("the job failed", "error", err)
				report(func(c context.Context) (bool, error) { return jobs.Fail(c, pool, job.ID, workerID, err.Error()) }, "failed")
			}
			return
		case <-jobCtx.Done():
			if ctx.Err() == nil {
				log.Warn("the job's lease ran out; no longer holding it")
			}
			return // on shutdown the lease is simply left: the backend takes the job back once its heartbeat goes quiet
		case <-ticker.C:
		}

		beat, err := jobs.Heartbeat(jobCtx, pool, job.ID, workerID)
		switch {
		case err != nil:
			if jobCtx.Err() == nil {
				log.Error("renewing the job's lease failed; will try again", "error", err) // the database may be briefly away
			}
		case !beat.HoldsLease:
			log.Warn("the job is no longer ours (taken back or ended); stopping")
			return
		case beat.StopRequested:
			stopJob() // the job stops here
			<-finished
			ran = true
			report(func(c context.Context) (bool, error) { return jobs.AcknowledgeStop(c, pool, job.ID, workerID) }, "was stopped because the deployment was cancelled")
			return
		}
	}
}
