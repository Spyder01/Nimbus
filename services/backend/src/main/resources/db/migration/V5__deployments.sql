-- A request to run a saved version of an app's graph. A worker (added later) claims QUEUED rows
-- under a lease, performs the deployment, and finishes the row.
CREATE TABLE deployments
(
    id                  UUID                     NOT NULL DEFAULT gen_random_uuid(),
    app_id              UUID                     NOT NULL REFERENCES apps (id) ON DELETE CASCADE,
    -- Always a SAVED version, so what was deployed can be seen and re-deployed later.
    spec_id             UUID                     NOT NULL REFERENCES app_specs (id),
    state               TEXT                     NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- Times a worker has claimed it. A stale lease re-queues it until the configured maximum.
    attempts            INT                      NOT NULL DEFAULT 0,

    -- Lease, set while a worker holds the row:
    --   lease_expires_at is fixed at claim time (acquired + max run time) and never extended;
    --   lease_updated_at is the worker's last heartbeat, so a dead worker is noticed well before expiry.
    lease_owner         TEXT,
    lease_acquired_at   TIMESTAMP WITH TIME ZONE,
    lease_expires_at    TIMESTAMP WITH TIME ZONE,
    lease_updated_at    TIMESTAMP WITH TIME ZONE,

    -- Set when a user cancels while IN_PROGRESS; the worker finishes the row as CANCELLED.
    cancel_requested_at TIMESTAMP WITH TIME ZONE,
    cancelled_by        UUID REFERENCES users (id) ON DELETE SET NULL,

    error               TEXT,
    requested_by        UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    started_at          TIMESTAMP WITH TIME ZONE,
    finished_at         TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

-- At most one queued/running deployment per app.
CREATE UNIQUE INDEX deployments_one_active_uq ON deployments (app_id) WHERE state IN ('QUEUED', 'IN_PROGRESS');
CREATE INDEX deployments_app_created_idx ON deployments (app_id, created_at DESC);
-- Worker scans: next queued rows, and in-progress rows to check for stale leases.
CREATE INDEX deployments_pending_idx ON deployments (state, created_at) WHERE state IN ('QUEUED', 'IN_PROGRESS');
