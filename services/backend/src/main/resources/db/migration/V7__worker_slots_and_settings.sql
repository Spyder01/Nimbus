-- Registered workers, and the settings that can be changed for them while they run.

-- One row per worker NAME ("default-0", "default-1", ...). A name is a slot: the process that holds it keeps it
-- alive by renewing lease_expires_at. A new worker takes over the lowest-numbered slot of its pool whose lease has
-- expired (inheriting the name and the settings stored under it), or adds the next slot if none has. A worker that
-- asks for an exact name (WORKER_NAME) gets that slot instead; those have no ordinal.
--
-- The slot is the stable identity. instance_id is the current process and changes on every restart: task leases
-- (deployment_tasks.lease_owner) use the instance, so a new process never inherits another process's task leases.
CREATE TABLE worker_slots
(
    name                     TEXT                     NOT NULL,
    pool                     TEXT                     NOT NULL,
    -- Position within the pool for dynamically assigned slots; NULL for slots a worker asked for by name.
    ordinal                  INT,
    -- The process currently holding the slot.
    instance_id              TEXT                     NOT NULL,
    version                  TEXT,
    host                     TEXT,
    -- When the current instance took the slot, and its last heartbeat (both from the database's clock).
    started_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_seen_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- The slot is free to take over once this has passed. Renewed by the heartbeat, only by the holding instance.
    lease_expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    -- What the worker reports having applied: the highest worker_settings.version it has seen (so the UI can show
    -- "pending" until it catches up), and why it refused them if it did.
    applied_settings_version BIGINT,
    settings_error           TEXT,
    PRIMARY KEY (name),
    CONSTRAINT worker_slots_name_format CHECK (name ~ '^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$'),
    CONSTRAINT worker_slots_pool_format CHECK (pool ~ '^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$'),
    CONSTRAINT worker_slots_ordinal_check CHECK (ordinal IS NULL OR ordinal >= 0),
    CONSTRAINT worker_slots_pool_ordinal_uq UNIQUE (pool, ordinal)
);

-- Finding the lowest expired slot of a pool when a worker registers.
CREATE INDEX worker_slots_takeover_idx ON worker_slots (pool, lease_expires_at, ordinal);

-- One counter for all settings rows, so a higher version always means a later change, whichever row it was on.
CREATE SEQUENCE worker_settings_version_seq;

-- Settings for a pool of workers (scope POOL) or for one worker (scope WORKER, keyed by its slot name). A worker's
-- effective value is its own, else its pool's, else its environment default. A NULL value means "inherit".
--
-- Keyed by name and deliberately not tied to worker_slots: an override must survive its slot row being removed and
-- come back when a worker takes that name again.
--
-- To go back to inheriting, set the value to NULL rather than deleting the row: every change gets a new version, and
-- workers notice changes by version.
CREATE TABLE worker_settings
(
    scope         TEXT                     NOT NULL CHECK (scope IN ('POOL', 'WORKER')),
    name          TEXT                     NOT NULL,
    -- Jobs the worker may run at once. The limits match the worker's own (config.MinParallelJobs/MaxParallelJobs).
    parallel_jobs INT CHECK (parallel_jobs BETWEEN 1 AND 100),
    -- Lease length for newly claimed jobs, in seconds (config.MinLeaseSeconds/MaxLeaseSeconds).
    lease_seconds INT CHECK (lease_seconds BETWEEN 60 AND 86400),
    version       BIGINT                   NOT NULL DEFAULT nextval('worker_settings_version_seq'),
    updated_by    UUID REFERENCES users (id) ON DELETE SET NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, name),
    CONSTRAINT worker_settings_name_format CHECK (name ~ '^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$')
);

-- Every insert or update gets a new version and update time, whoever makes it (the backend, a worker, or a person with
-- a SQL prompt), so a change can't go unnoticed by forgetting to bump the version.
CREATE FUNCTION worker_settings_touch() RETURNS trigger AS
$$
BEGIN
    NEW.version := nextval('worker_settings_version_seq');
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER worker_settings_touch
    BEFORE INSERT OR UPDATE
    ON worker_settings
    FOR EACH ROW
EXECUTE FUNCTION worker_settings_touch();
