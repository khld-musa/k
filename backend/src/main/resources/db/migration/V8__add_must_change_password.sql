-- V8__add_must_change_password.sql
-- Adds a flag to force password change on first login

ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.must_change_password IS 'If true, user must change password before accessing the system';

