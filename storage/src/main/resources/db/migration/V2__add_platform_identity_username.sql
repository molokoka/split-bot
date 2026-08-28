ALTER TABLE platform_identity ADD COLUMN username TEXT;

CREATE INDEX idx_platform_identity_username
    ON platform_identity(platform, username) WHERE username IS NOT NULL;
