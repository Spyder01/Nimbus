-- A person in the app. Provider-specific data lives in user_identities.
CREATE TABLE users
(
    id         UUID                     NOT NULL DEFAULT gen_random_uuid(),
    name       TEXT,
    -- Display/contact email copied from the provider. Not unique and not used to link accounts:
    -- provider emails can be unverified, so matching on them would allow account takeover.
    email      TEXT,
    avatar_url TEXT,
    role       TEXT                     NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

-- One row per way a user can log in (github, google, ...).
CREATE TABLE user_identities
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    user_id          UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider         TEXT                     NOT NULL,   -- OAuth registration id: 'github', 'google', ...
    provider_user_id TEXT                     NOT NULL,   -- stable id from the provider (GitHub numeric id, Google 'sub')
    login            TEXT,                                -- provider username/handle (GitHub login)
    email            TEXT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (provider, provider_user_id)
);

CREATE INDEX user_identities_user_id_idx ON user_identities (user_id);
