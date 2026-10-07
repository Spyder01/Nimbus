// Package registry gives each worker a stable name, held under a lease, in the backend's worker_slots table.
//
// A name is a slot ("default-0", "default-1", ...). The process holding a slot keeps it by renewing its lease
// (Heartbeat). A worker that registers takes over the lowest-numbered slot of its pool whose lease has expired,
// so it inherits that name and the settings stored under it, or adds the next slot if none has. A worker may
// instead ask for an exact name. Release gives the slot up at once, so a restart finds it free.
//
// The slot is the stable identity (it keeps its name and settings across restarts); instanceID is this process and is
// new on every start. The two are separate on purpose: the slot's lease is how a restarted worker finds its old name,
// while task leases (deployment_tasks.lease_owner) use the instance id, so a new process never inherits the old
// process's job leases.
//
// Times come from the database's clock, so every worker and the backend agree. The backend owns the schema.
package registry

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var (
	// ErrSlotLost means the worker no longer holds its slot: the lease ran out, or another worker took the slot over.
	// The worker should stop claiming jobs, and register again.
	ErrSlotLost = errors.New("the worker no longer holds its slot: it expired or was taken over")

	// ErrNameInUse means another live worker holds the name that was asked for.
	ErrNameInUse = errors.New("another live worker holds that name")

	// ErrNameInOtherPool means the name that was asked for already belongs to a different pool.
	ErrNameInOtherPool = errors.New("that name belongs to a different pool")
)

// The same format the database enforces on names and pools: lowercase letters, digits and '-', 1 to 63 characters.
var nameFormat = regexp.MustCompile(`^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$`)

// How a registration ended up with its slot.
type Outcome string

const (
	// Created: a new slot was added.
	Created Outcome = "created"
	// TookOver: an expired slot was inherited, with its name and settings.
	TookOver Outcome = "took over"
	// Renewed: this instance already held the slot (registering again is harmless).
	Renewed Outcome = "renewed"
)

// Registration is what a worker presents when it starts.
type Registration struct {
	// Pool the worker belongs to. Required.
	Pool string
	// Name asks for this exact slot. Optional: leave empty to be given one.
	Name string
	// InstanceID identifies this process; unique per start. Required.
	InstanceID string
	// Lease is how long a registration or renewal lasts. Required.
	Lease time.Duration
	// Version and Host are shown next to the worker. Optional.
	Version string
	Host    string
}

// Slot is the registered worker.
type Slot struct {
	Name           string
	Pool           string
	InstanceID     string
	LeaseExpiresAt time.Time
	Outcome        Outcome
	// PreviousInstanceID is who held the slot before, when this registration took it over.
	PreviousInstanceID string
}

const (
	// Registrations to one pool are made one at a time, so two workers can't pick the same ordinal.
	queryLockPool = `SELECT pg_advisory_xact_lock(hashtextextended('nimbus-worker-slots:' || $1, 0))`

	// The slot this instance already holds in the pool, if any.
	queryOwnSlot = `SELECT name FROM worker_slots WHERE pool = $1 AND instance_id = $2 FOR UPDATE`

	// The lowest-numbered dynamic slot of the pool whose lease has run out. Slots a worker asked for by name (no
	// ordinal) are only ever taken over by a worker asking for that name.
	queryExpiredSlot = `
		SELECT name, instance_id
		FROM worker_slots
		WHERE pool = $1 AND ordinal IS NOT NULL AND lease_expires_at < clock_timestamp()
		ORDER BY ordinal
		LIMIT 1
		FOR UPDATE`

	queryNamedSlot = `SELECT pool, instance_id, lease_expires_at > clock_timestamp() FROM worker_slots WHERE name = $1 FOR UPDATE`

	queryNextOrdinal = `SELECT COALESCE(MAX(ordinal) + 1, 0) FROM worker_slots WHERE pool = $1`

	// Gives the slot to this instance. The time is read once, so everything starts at the same instant. A slot that
	// is taken over starts with no applied settings: the new process hasn't applied anything yet.
	queryTakeOver = `
		WITH t AS (SELECT clock_timestamp() AS at)
		UPDATE worker_slots
		SET instance_id              = $2,
		    version                  = $3,
		    host                     = $4,
		    started_at               = t.at,
		    last_seen_at             = t.at,
		    lease_expires_at         = t.at + make_interval(secs => $5::double precision),
		    applied_settings_version = NULL,
		    settings_error           = NULL
		FROM t
		WHERE name = $1 AND lease_expires_at < clock_timestamp()
		RETURNING name, pool, instance_id, lease_expires_at`

	// Registering again from the same instance only renews.
	queryRenewOwn = `
		WITH t AS (SELECT clock_timestamp() AS at)
		UPDATE worker_slots
		SET version = $3, host = $4, last_seen_at = t.at, lease_expires_at = t.at + make_interval(secs => $5::double precision)
		FROM t
		WHERE name = $1 AND instance_id = $2
		RETURNING name, pool, instance_id, lease_expires_at`

	// A new slot. ON CONFLICT DO NOTHING covers a generated name that an explicitly named slot already uses.
	queryCreate = `
		INSERT INTO worker_slots (name, pool, ordinal, instance_id, version, host, started_at, last_seen_at, lease_expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, clock_timestamp(), clock_timestamp(), clock_timestamp() + make_interval(secs => $7::double precision))
		ON CONFLICT (name) DO NOTHING
		RETURNING name, pool, instance_id, lease_expires_at`

	// Renews the lease. Only the instance holding the slot can, and only before it has expired: an expired slot may
	// already be someone else's, so it is never revived here.
	queryHeartbeat = `
		WITH t AS (SELECT clock_timestamp() AS at)
		UPDATE worker_slots
		SET last_seen_at = t.at, lease_expires_at = t.at + make_interval(secs => $3::double precision)
		FROM t
		WHERE name = $1 AND instance_id = $2 AND lease_expires_at > clock_timestamp()
		RETURNING lease_expires_at`

	// Ends the lease now, so the slot can be taken over straight away.
	queryRelease = `UPDATE worker_slots SET lease_expires_at = clock_timestamp() WHERE name = $1 AND instance_id = $2`
)

// maxNameClashes bounds how many ordinals are tried when a generated name is already used by an explicitly named slot.
const maxNameClashes = 50

// Register gives the worker a slot, as described in the package comment, and returns it.
// Registering again from the same instance just renews the lease.
func Register(ctx context.Context, pool *pgxpool.Pool, r Registration) (Slot, error) {
	if err := r.validate(); err != nil {
		return Slot{}, err
	}

	tx, err := pool.Begin(ctx)
	if err != nil {
		return Slot{}, fmt.Errorf("starting registration: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }() // does nothing once committed

	if _, err := tx.Exec(ctx, queryLockPool, r.Pool); err != nil {
		return Slot{}, fmt.Errorf("locking pool %q: %w", r.Pool, err)
	}

	var slot Slot
	if r.Name != "" {
		slot, err = registerNamed(ctx, tx, r)
	} else {
		slot, err = registerDynamic(ctx, tx, r)
	}
	if err != nil {
		return Slot{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return Slot{}, fmt.Errorf("committing registration: %w", err)
	}
	return slot, nil
}

func registerDynamic(ctx context.Context, tx pgx.Tx, r Registration) (Slot, error) {
	// Already ours (registering twice, or coming back after a lapse that nobody took advantage of)?
	var own string
	switch err := tx.QueryRow(ctx, queryOwnSlot, r.Pool, r.InstanceID).Scan(&own); {
	case err == nil:
		return renewOwn(ctx, tx, own, r)
	case !errors.Is(err, pgx.ErrNoRows):
		return Slot{}, fmt.Errorf("looking for the worker's slot: %w", err)
	}

	// Inherit the lowest expired slot.
	var name, previous string
	switch err := tx.QueryRow(ctx, queryExpiredSlot, r.Pool).Scan(&name, &previous); {
	case err == nil:
		slot, err := scanSlot(tx.QueryRow(ctx, queryTakeOver, name, r.InstanceID, nullable(r.Version), nullable(r.Host), r.Lease.Seconds()))
		if err != nil {
			return Slot{}, fmt.Errorf("taking over %s: %w", name, err)
		}
		slot.Outcome, slot.PreviousInstanceID = TookOver, previous
		return slot, nil
	case !errors.Is(err, pgx.ErrNoRows):
		return Slot{}, fmt.Errorf("looking for an expired slot: %w", err)
	}

	// None free: add the next one.
	var ordinal int
	if err := tx.QueryRow(ctx, queryNextOrdinal, r.Pool).Scan(&ordinal); err != nil {
		return Slot{}, fmt.Errorf("choosing the next slot number: %w", err)
	}
	for i := 0; i < maxNameClashes; i, ordinal = i+1, ordinal+1 {
		name := r.Pool + "-" + strconv.Itoa(ordinal)
		if !nameFormat.MatchString(name) {
			return Slot{}, fmt.Errorf("pool %q is too long to build slot names from (%q is not a valid name)", r.Pool, name)
		}
		slot, err := scanSlot(tx.QueryRow(ctx, queryCreate, name, r.Pool, ordinal, r.InstanceID, nullable(r.Version), nullable(r.Host), r.Lease.Seconds()))
		if errors.Is(err, pgx.ErrNoRows) {
			continue // the name is taken by an explicitly named slot: try the next number
		}
		if err != nil {
			return Slot{}, fmt.Errorf("creating %s: %w", name, err)
		}
		slot.Outcome = Created
		return slot, nil
	}
	return Slot{}, fmt.Errorf("no free slot name found in pool %q after %d tries", r.Pool, maxNameClashes)
}

func registerNamed(ctx context.Context, tx pgx.Tx, r Registration) (Slot, error) {
	var pool, holder string
	var live bool
	err := tx.QueryRow(ctx, queryNamedSlot, r.Name).Scan(&pool, &holder, &live)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		slot, err := scanSlot(tx.QueryRow(ctx, queryCreate, r.Name, r.Pool, nil, r.InstanceID, nullable(r.Version), nullable(r.Host), r.Lease.Seconds()))
		if err != nil {
			return Slot{}, fmt.Errorf("creating %s: %w", r.Name, err)
		}
		slot.Outcome = Created
		return slot, nil
	case err != nil:
		return Slot{}, fmt.Errorf("looking up %s: %w", r.Name, err)
	case pool != r.Pool:
		return Slot{}, fmt.Errorf("%w: %q is in pool %q, not %q", ErrNameInOtherPool, r.Name, pool, r.Pool)
	case holder == r.InstanceID:
		return renewOwn(ctx, tx, r.Name, r) // ours, even if the lease had lapsed: nobody has replaced us
	case live:
		return Slot{}, fmt.Errorf("%w: %q is held by %s", ErrNameInUse, r.Name, holder)
	}
	slot, err := scanSlot(tx.QueryRow(ctx, queryTakeOver, r.Name, r.InstanceID, nullable(r.Version), nullable(r.Host), r.Lease.Seconds()))
	if err != nil {
		return Slot{}, fmt.Errorf("taking over %s: %w", r.Name, err)
	}
	slot.Outcome, slot.PreviousInstanceID = TookOver, holder
	return slot, nil
}

func renewOwn(ctx context.Context, tx pgx.Tx, name string, r Registration) (Slot, error) {
	slot, err := scanSlot(tx.QueryRow(ctx, queryRenewOwn, name, r.InstanceID, nullable(r.Version), nullable(r.Host), r.Lease.Seconds()))
	if err != nil {
		return Slot{}, fmt.Errorf("renewing %s: %w", name, err)
	}
	slot.Outcome = Renewed
	return slot, nil
}

// Heartbeat renews the lease of the slot name for instanceID, for another lease from now, and returns when it now
// expires. It returns ErrSlotLost if the instance doesn't hold the slot any more (the lease had already run out, or
// someone else took the slot over); an expired slot is never revived, because it may already be someone else's.
func Heartbeat(ctx context.Context, pool *pgxpool.Pool, name, instanceID string, lease time.Duration) (time.Time, error) {
	if name == "" || instanceID == "" || lease <= 0 {
		return time.Time{}, errors.New("name, instanceID and a positive lease are required")
	}
	var expires time.Time
	err := pool.QueryRow(ctx, queryHeartbeat, name, instanceID, lease.Seconds()).Scan(&expires)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, ErrSlotLost
	}
	if err != nil {
		return time.Time{}, fmt.Errorf("renewing slot %s: %w", name, err)
	}
	return expires, nil
}

// Release gives the slot up now, so the next worker to register inherits it without waiting for the lease to run
// out. It does nothing if the instance doesn't hold the slot (it may already have been taken over).
func Release(ctx context.Context, pool *pgxpool.Pool, name, instanceID string) error {
	if name == "" || instanceID == "" {
		return errors.New("name and instanceID are required")
	}
	if _, err := pool.Exec(ctx, queryRelease, name, instanceID); err != nil {
		return fmt.Errorf("releasing slot %s: %w", name, err)
	}
	return nil
}

func (r Registration) validate() error {
	switch {
	case !nameFormat.MatchString(r.Pool):
		return fmt.Errorf("pool %q is not a valid name: use lowercase letters, digits and '-', 1 to 63 characters", r.Pool)
	case r.Name != "" && !nameFormat.MatchString(r.Name):
		return fmt.Errorf("name %q is not a valid name: use lowercase letters, digits and '-', 1 to 63 characters", r.Name)
	case r.InstanceID == "":
		return errors.New("instanceID is required")
	case r.Lease <= 0:
		return fmt.Errorf("lease must be positive, got %s", r.Lease)
	}
	return nil
}

func scanSlot(row pgx.Row) (Slot, error) {
	var s Slot
	err := row.Scan(&s.Name, &s.Pool, &s.InstanceID, &s.LeaseExpiresAt)
	return s, err
}

// nullable turns an empty string into SQL NULL.
func nullable(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
