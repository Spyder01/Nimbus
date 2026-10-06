-- A deployment is now made of tasks, one per container, planned when Deploy is clicked.
-- The lease moves from the deployment to each task, and the deployment's state follows its tasks.

ALTER TABLE deployments
    DROP COLUMN attempts,
    DROP COLUMN lease_owner,
    DROP COLUMN lease_acquired_at,
    DROP COLUMN lease_expires_at,
    DROP COLUMN lease_updated_at;

-- Set when a task fails permanently: tasks that haven't started are cancelled and running ones stop at
-- their next heartbeat; the deployment ends FAILED once nothing is running.
ALTER TABLE deployments
    ADD COLUMN abort_requested_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE deployment_tasks
(
    id                UUID                     NOT NULL DEFAULT gen_random_uuid(),
    deployment_id     UUID                     NOT NULL REFERENCES deployments (id) ON DELETE CASCADE,
    -- Which container of the graph this deploys.
    node_id           TEXT                     NOT NULL,
    name              TEXT                     NOT NULL,
    -- Topological position: tasks in the same layer don't depend on each other and can run in parallel;
    -- `ordinal` is the total order (layer first) used for display and tie-breaking.
    layer             INT                      NOT NULL,
    ordinal           INT                      NOT NULL,
    -- Tasks that must be SUCCEEDED before this one can start (the containers this one needs).
    depends_on        UUID[]                   NOT NULL DEFAULT '{}',
    -- Snapshot of the container's configuration, so a worker needs nothing else to deploy it.
    spec              JSONB                    NOT NULL,
    -- PENDING: waiting for dependencies. QUEUED: ready to be claimed. IN_PROGRESS: leased by a worker.
    state             TEXT                     NOT NULL
        CHECK (state IN ('PENDING', 'QUEUED', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- Times a worker has claimed it. A stale lease re-queues it until the configured maximum.
    attempts          INT                      NOT NULL DEFAULT 0,

    -- Lease, set while a worker holds the task:
    --   lease_expires_at is fixed at claim time (acquired + max run time) and never extended;
    --   lease_updated_at is the worker's last heartbeat.
    lease_owner       TEXT,
    lease_acquired_at TIMESTAMP WITH TIME ZONE,
    lease_expires_at  TIMESTAMP WITH TIME ZONE,
    lease_updated_at  TIMESTAMP WITH TIME ZONE,

    error             TEXT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    started_at        TIMESTAMP WITH TIME ZONE,
    finished_at       TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    UNIQUE (deployment_id, node_id)
);

CREATE INDEX deployment_tasks_deployment_idx ON deployment_tasks (deployment_id, ordinal);
-- Worker scans: next ready tasks, and leased tasks to check for stale leases.
CREATE INDEX deployment_tasks_ready_idx ON deployment_tasks (created_at, ordinal) WHERE state = 'QUEUED';
CREATE INDEX deployment_tasks_leased_idx ON deployment_tasks (lease_expires_at) WHERE state = 'IN_PROGRESS';
