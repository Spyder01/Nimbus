-- false until the user has saved their profile in the app; the UI prompts for it while false,
-- and provider logins stop overwriting name/email/avatar once it is true.
ALTER TABLE users
    ADD COLUMN profile_updated BOOLEAN NOT NULL DEFAULT false;
