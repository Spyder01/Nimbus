-- A role above ADMIN: the only one that can make other people admins. It is never assigned through the API, only in
-- the database, so the first one has to be set by hand (UPDATE users SET role = 'SUPER_ADMIN' WHERE ...).
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN', 'SUPER_ADMIN'));
