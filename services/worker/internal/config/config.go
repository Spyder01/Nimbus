// Package config holds the worker's settings. Call Load once at startup; after that any package can read the
// same values with Get (fixed for the life of the process) and ParallelJobs (changeable while running).
package config

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/joho/godotenv"
)

const (
	DefaultParallelJobs = 4
	MinParallelJobs     = 1
	MaxParallelJobs     = 100

	// How long a claimed job is leased. The default matches the backend's nimbus.deployments.lease-duration.
	// Under a minute can't outlast a heartbeat interval; over a day is a job that has gone wrong.
	DefaultLeaseSeconds = 30 * 60
	MinLeaseSeconds     = 60
	MaxLeaseSeconds     = 24 * 60 * 60
)

// DBConfig holds the Postgres connection settings. They come from the same POSTGRES_* variables as the backend.
type DBConfig struct {
	Host     string
	Port     int
	Name     string
	User     string
	Password string
}

// Config is the part of the settings that is fixed once loaded.
type Config struct {
	// WorkerID identifies this worker process. It is new on every start, so a restarted worker is a different
	// worker and never inherits the previous run's leases.
	WorkerID string
	// Pool groups workers that share default settings. Slots are numbered per pool (default-0, default-1, ...).
	Pool string
	// Name asks for this exact worker name instead of being given one. Optional; empty means "assign one".
	Name string
	DB   DBConfig
}

var (
	once    sync.Once
	loadErr error
	current atomic.Pointer[Config]

	// These two can change while the worker runs (see Update), so they are kept apart from Config and read
	// through ParallelJobs and LeaseDuration: the number of jobs the worker may run at the same time, and how
	// long a newly claimed job is leased for.
	parallelJobs atomic.Int32
	leaseSeconds atomic.Int32
	updateMu     sync.Mutex // makes an Update a single step: two overlapping updates can't end up half and half

	// What those two were when the worker started (from the environment, or the defaults): the values to fall back
	// to when no stored setting applies.
	starting atomic.Pointer[startingValues]
)

// Load reads the configuration the first time it is called and returns that same value on every later call
// (including the same error, if the first call failed), so the settings can't change while the worker runs.
//
// It reads a .env file from the working directory if there is one, then the environment. Variables already
// set in the environment win over the file, so a deployment can override it.
func Load() (Config, error) {
	once.Do(func() {
		cfg, start, err := load()
		if err != nil {
			loadErr = err
			return
		}
		parallelJobs.Store(int32(start.parallelJobs))
		leaseSeconds.Store(int32(start.leaseSeconds))
		starting.Store(&start)
		current.Store(&cfg)
	})
	if loadErr != nil {
		return Config{}, loadErr
	}
	return *current.Load(), nil
}

// Get returns the configuration loaded by Load. It is a copy, so callers can't modify the shared value.
// It panics if Load hasn't succeeded: that is a bug in the program's startup order, not a runtime condition.
func Get() Config {
	cfg := current.Load()
	if cfg == nil {
		panic("config.Get called before a successful config.Load")
	}
	return *cfg
}

// ParallelJobs is the number of jobs the worker may run at the same time right now. Read it each time it is
// needed rather than keeping a copy, so a change made with Update takes effect.
func ParallelJobs() int {
	Get() // panics if Load hasn't succeeded
	return int(parallelJobs.Load())
}

// LeaseDuration is how long a job claimed now is leased for. Read it each time a job is claimed. A change affects
// jobs claimed after it; leases already taken keep the length they were given.
func LeaseDuration() time.Duration {
	Get() // panics if Load hasn't succeeded
	return time.Duration(leaseSeconds.Load()) * time.Second
}

// Starting returns the values of the live settings as the worker started with them (WORKER_PARALLEL_JOBS and
// WORKER_LEASE_SECONDS, or the defaults). They are what applies when no stored setting does.
func Starting() (parallelJobs, leaseSeconds int) {
	Get() // panics if Load hasn't succeeded
	s := starting.Load()
	return s.parallelJobs, s.leaseSeconds
}

// Changes is a set of runtime settings to change. A nil field is left as it is.
type Changes struct {
	ParallelJobs *int
	LeaseSeconds *int
}

// Update changes settings while the worker is running. It is all or nothing: if any value is out of range, the
// error says so and nothing changes. Changes are kept in memory only: a restart goes back to the starting values
// (WORKER_PARALLEL_JOBS and WORKER_LEASE_SECONDS, or the defaults).
func Update(c Changes) error {
	Get() // panics if Load hasn't succeeded
	var errs []error
	if c.ParallelJobs != nil {
		errs = append(errs, validateParallelJobs(*c.ParallelJobs))
	}
	if c.LeaseSeconds != nil {
		errs = append(errs, validateLeaseSeconds(*c.LeaseSeconds))
	}
	if err := errors.Join(errs...); err != nil {
		return err
	}

	updateMu.Lock()
	defer updateMu.Unlock()
	if c.ParallelJobs != nil {
		parallelJobs.Store(int32(*c.ParallelJobs))
	}
	if c.LeaseSeconds != nil {
		leaseSeconds.Store(int32(*c.LeaseSeconds))
	}
	return nil
}

func validateParallelJobs(n int) error {
	if n < MinParallelJobs || n > MaxParallelJobs {
		return fmt.Errorf("parallelJobs must be between %d and %d, got %d", MinParallelJobs, MaxParallelJobs, n)
	}
	return nil
}

func validateLeaseSeconds(n int) error {
	if n < MinLeaseSeconds || n > MaxLeaseSeconds {
		return fmt.Errorf("leaseSeconds must be between %d and %d, got %d", MinLeaseSeconds, MaxLeaseSeconds, n)
	}
	return nil
}

// startingValues are the runtime-changeable settings as first loaded.
type startingValues struct{ parallelJobs, leaseSeconds int }

func load() (Config, startingValues, error) {
	if err := godotenv.Load(); err != nil && !errors.Is(err, os.ErrNotExist) {
		return Config{}, startingValues{}, fmt.Errorf("reading .env: %w", err)
	}

	var missing []string
	get := func(key string) string {
		v := os.Getenv(key)
		if v == "" {
			missing = append(missing, key)
		}
		return v
	}

	cfg := Config{
		WorkerID: newWorkerID(),
		Pool:     envOr("WORKER_POOL", "default"),
		Name:     os.Getenv("WORKER_NAME"),
		DB: DBConfig{
			Host:     get("POSTGRES_HOST"),
			Name:     get("POSTGRES_DB"),
			User:     get("POSTGRES_USER"),
			Password: get("POSTGRES_PASSWORD"),
		},
	}
	port := get("POSTGRES_PORT")

	var errs []error
	if len(missing) > 0 {
		errs = append(errs, fmt.Errorf("missing environment variables: %s", strings.Join(missing, ", ")))
	}
	if port != "" {
		n, err := strconv.Atoi(port)
		if err != nil || n < 1 || n > 65535 {
			errs = append(errs, fmt.Errorf("POSTGRES_PORT must be a port number between 1 and 65535, got %q", port))
		}
		cfg.DB.Port = n
	}

	// Optional: the starting values of the settings that can change while running.
	start := startingValues{parallelJobs: DefaultParallelJobs, leaseSeconds: DefaultLeaseSeconds}
	optional := func(key string, validate func(int) error, into *int) {
		v := os.Getenv(key)
		if v == "" {
			return
		}
		n, err := strconv.Atoi(v)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s must be a whole number, got %q", key, v))
		} else if err := validate(n); err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", key, err))
		} else {
			*into = n
		}
	}
	optional("WORKER_PARALLEL_JOBS", validateParallelJobs, &start.parallelJobs)
	optional("WORKER_LEASE_SECONDS", validateLeaseSeconds, &start.leaseSeconds)
	return cfg, start, errors.Join(errs...)
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// newWorkerID is the host name (the pod name in Kubernetes) plus a random suffix, so two workers never share an
// id even if they share a host name, and a restart gets a new one.
func newWorkerID() string {
	host, err := os.Hostname()
	if err != nil || host == "" {
		host = "worker"
	}
	b := make([]byte, 4)
	if _, err := rand.Read(b); err != nil {
		panic(err) // the OS random source failing is not recoverable
	}
	return host + "-" + hex.EncodeToString(b)
}
