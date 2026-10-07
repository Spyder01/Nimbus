// Package membership is the worker's standing in the cluster: it registers for a named slot, keeps it with a
// heartbeat, applies the settings stored for that name, and gives the slot up when the worker stops.
//
// Use it as: Join at startup, Run in its own goroutine, Registered before each claim, Leave on the way out.
//
// A worker that can't confirm its slot (it was taken over, or the database has been unreachable for most of a lease)
// reports Registered() == false, so it claims nothing, and keeps trying to register again.
package membership

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"nimbus/worker/internal/config"
	"nimbus/worker/internal/registry"
)

// Options say who the worker is. Pool, InstanceID and the database are required; the rest have defaults.
type Options struct {
	Pool       string
	InstanceID string
	// Name asks for an exact worker name instead of being given one.
	Name    string
	Version string
	Host    string

	// Lease is how long the slot stays ours without a heartbeat. Default 30s.
	Lease time.Duration
	// Interval is how often the heartbeat runs; keep it well under Lease. Default 10s.
	Interval time.Duration
	// WaitForName is how long to keep trying when the name asked for is held by a live worker (typically the previous
	// run, still inside its lease). Default twice Lease.
	WaitForName time.Duration
}

func (o Options) withDefaults() Options {
	if o.Lease <= 0 {
		o.Lease = 30 * time.Second
	}
	if o.Interval <= 0 {
		o.Interval = 10 * time.Second
	}
	if o.WaitForName <= 0 {
		o.WaitForName = 2 * o.Lease
	}
	return o
}

type Membership struct {
	db *pgxpool.Pool
	o  Options

	mu         sync.RWMutex
	slot       registry.Slot
	hasSlot    bool
	validUntil time.Time // the slot is only trusted until here, by this process's own clock

	// Touched only by the goroutine running Join and then Run, one after the other.
	synced      bool  // settings have been applied for the current slot
	lastVersion int64 // the stored settings version last applied
}

// Join registers the worker, applies the settings stored for its name, and returns once it is a member. When a
// requested name is held by a live worker it waits for that lease to run out, up to WaitForName.
func Join(ctx context.Context, db *pgxpool.Pool, o Options) (*Membership, error) {
	m := &Membership{db: db, o: o.withDefaults()}
	if err := m.register(ctx, true); err != nil {
		return nil, err
	}
	m.syncSettings(ctx) // so the very first claim already uses the right settings
	return m, nil
}

// Registered reports whether the worker may claim jobs: it holds a slot, and its lease can still be trusted.
func (m *Membership) Registered() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.hasSlot && time.Now().Before(m.validUntil)
}

// Name is the worker's name, or "" while it has none.
func (m *Membership) Name() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if !m.hasSlot {
		return ""
	}
	return m.slot.Name
}

// Run keeps the slot alive and the settings current until ctx is cancelled.
func (m *Membership) Run(ctx context.Context) {
	ticker := time.NewTicker(m.o.Interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			m.tick(ctx)
		}
	}
}

// Leave gives the slot up so a replacement can take it straight away. Call it after Run has returned.
func (m *Membership) Leave() {
	name := m.Name()
	if name == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := registry.Release(ctx, m.db, name, m.o.InstanceID); err != nil {
		slog.Warn("could not release the worker slot; it will expire on its own", "worker_name", name, "error", err)
		return
	}
	slog.Info("released the worker slot", "worker_name", name)
}

func (m *Membership) tick(ctx context.Context) {
	if name := m.Name(); name != "" {
		_, err := registry.Heartbeat(ctx, m.db, name, m.o.InstanceID, m.o.Lease)
		switch {
		case err == nil:
			m.extend()
		case errors.Is(err, registry.ErrSlotLost):
			slog.Warn("the worker slot was lost: not claiming until it is registered again", "worker_name", name)
			m.drop()
		default:
			// Probably the database for a moment. The slot stays ours until its lease runs out; Registered() turns
			// false before that happens, so nothing is claimed on a lease that may be gone.
			if ctx.Err() == nil {
				slog.Error("worker heartbeat failed", "worker_name", name, "error", err)
			}
			return
		}
	}
	if !m.hasSlotNow() {
		if err := m.register(ctx, false); err != nil {
			if ctx.Err() == nil {
				slog.Error("could not register the worker", "error", err)
			}
			return
		}
	}
	m.syncSettings(ctx)
}

func (m *Membership) hasSlotNow() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.hasSlot
}

// register takes a slot. With wait set it keeps trying while a requested name is held by a live worker.
func (m *Membership) register(ctx context.Context, wait bool) error {
	deadline := time.Now().Add(m.o.WaitForName)
	for {
		slot, err := registry.Register(ctx, m.db, registry.Registration{
			Pool: m.o.Pool, Name: m.o.Name, InstanceID: m.o.InstanceID,
			Lease: m.o.Lease, Version: m.o.Version, Host: m.o.Host,
		})
		if err == nil {
			m.mu.Lock()
			previous := m.slot.Name
			m.slot, m.hasSlot = slot, true
			m.validUntil = m.trustedUntil()
			m.mu.Unlock()
			m.synced = false // a new slot may carry different settings
			slog.Info("registered the worker",
				"worker_name", slot.Name, "pool", slot.Pool, "outcome", string(slot.Outcome), "previous_instance", slot.PreviousInstanceID)
			if previous != "" && previous != slot.Name {
				slog.Warn("the worker's name changed", "from", previous, "to", slot.Name)
			}
			return nil
		}
		if !wait || !errors.Is(err, registry.ErrNameInUse) || time.Now().After(deadline) {
			return err
		}
		slog.Info("the requested worker name is still held by a live worker; waiting for it to expire", "error", err.Error())
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(3 * time.Second):
		}
	}
}

// trustedUntil is how long, by our own clock, a lease taken now can be relied on. A sixth of the lease is held back,
// so a heartbeat that is late doesn't leave us claiming on a lease that has just run out.
func (m *Membership) trustedUntil() time.Time {
	return time.Now().Add(m.o.Lease - m.o.Lease/6)
}

func (m *Membership) extend() {
	m.mu.Lock()
	m.validUntil = m.trustedUntil()
	m.mu.Unlock()
}

func (m *Membership) drop() {
	m.mu.Lock()
	m.hasSlot = false
	m.mu.Unlock()
}

// syncSettings applies the settings stored for this worker when they have changed since the last time.
//
// The value that applies is the worker's own, else its pool's, else what it started with. It acts only when the stored
// version changes (or for a new slot), not on every tick, so a value set directly with PUT /config isn't undone until
// someone changes the stored settings.
func (m *Membership) syncSettings(ctx context.Context) {
	name := m.Name()
	if name == "" {
		return
	}
	stored, err := registry.ReadSettings(ctx, m.db, m.o.Pool, name)
	if err != nil {
		if ctx.Err() == nil {
			slog.Error("could not read the worker settings", "worker_name", name, "error", err)
		}
		return
	}
	if m.synced && stored.Version == m.lastVersion {
		return
	}

	startJobs, startLease := config.Starting()
	jobs, lease := startJobs, startLease
	if stored.ParallelJobs != nil {
		jobs = *stored.ParallelJobs
	}
	if stored.LeaseSeconds != nil {
		lease = *stored.LeaseSeconds
	}

	m.synced, m.lastVersion = true, stored.Version // whatever happens next, don't retry this version every tick
	if err := config.Update(config.Changes{ParallelJobs: &jobs, LeaseSeconds: &lease}); err != nil {
		slog.Error("the stored settings were refused", "worker_name", name, "version", stored.Version, "error", err)
		m.report(ctx, name, func(c context.Context) error {
			return registry.ReportProblem(c, m.db, name, m.o.InstanceID, fmt.Errorf("version %d refused: %w", stored.Version, err))
		})
		return
	}
	slog.Info("applied the stored settings", "worker_name", name, "version", stored.Version, "parallel_jobs", jobs, "lease_seconds", lease)
	m.report(ctx, name, func(c context.Context) error {
		return registry.ReportApplied(c, m.db, name, m.o.InstanceID, stored.Version)
	})
}

func (m *Membership) report(ctx context.Context, name string, f func(context.Context) error) {
	if err := f(ctx); err != nil && ctx.Err() == nil {
		slog.Warn("could not report the applied settings", "worker_name", name, "error", err)
	}
}
