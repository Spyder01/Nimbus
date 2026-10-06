-- An application the user designs on the canvas. The graph itself lives in app_specs.
CREATE TABLE apps
(
    id              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    owner_id        UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            TEXT                     NOT NULL,
    -- Lifecycle state. Only DRAFT is used until deployment exists.
    state           TEXT                     NOT NULL DEFAULT 'DRAFT'
        CHECK (state IN ('DRAFT', 'DEPLOYING', 'RUNNING', 'DEGRADED', 'FAILED', 'STOPPED', 'DELETING')),
    -- Denormalised from the draft so the dashboard list never has to read the graph.
    component_count INT                      NOT NULL DEFAULT 0,
    -- Last saved-version number handed out for this app.
    version_seq     INT                      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

-- App names are unique per owner, ignoring case.
CREATE UNIQUE INDEX apps_owner_name_uq ON apps (owner_id, lower(name));
CREATE INDEX apps_owner_updated_idx ON apps (owner_id, updated_at DESC);

-- The design of an app as (nodes, edges), shaped like React Flow's graph.
--   DRAFT: exactly one per app, overwritten by autosave. `revision` counts its updates and is the
--          client's optimistic-concurrency token.
--   SAVED: immutable snapshot. `revision` is the version number (1, 2, 3 ...).
CREATE TABLE app_specs
(
    id             UUID                     NOT NULL DEFAULT gen_random_uuid(),
    app_id         UUID                     NOT NULL REFERENCES apps (id) ON DELETE CASCADE,
    kind           TEXT                     NOT NULL CHECK (kind IN ('DRAFT', 'SAVED')),
    revision       BIGINT                   NOT NULL,
    nodes          JSONB                    NOT NULL DEFAULT '[]',
    edges          JSONB                    NOT NULL DEFAULT '[]',
    schema_version INT                      NOT NULL DEFAULT 1,
    -- sha256 of the canonical graph; used to skip saving a version identical to the latest.
    content_hash   TEXT                     NOT NULL,
    note           TEXT,
    created_by     UUID REFERENCES users (id) ON DELETE SET NULL,
    lock_version   BIGINT                   NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX app_specs_one_draft_uq ON app_specs (app_id) WHERE kind = 'DRAFT';
CREATE UNIQUE INDEX app_specs_saved_revision_uq ON app_specs (app_id, revision) WHERE kind = 'SAVED';
