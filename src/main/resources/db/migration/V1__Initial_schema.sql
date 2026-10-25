-- SafeHaven initial schema.
--
-- This script is intentionally idempotent (IF NOT EXISTS everywhere) because
-- Flyway baselines existing non-empty databases at version 0 before running
-- V1. That lets legacy installations that already contain the base tables,
-- data, and possibly the RSA/FEK columns or indexes migrate without error or
-- data loss.

-- ─── USERS ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS USERS (
    username VARCHAR(255) PRIMARY KEY,
    password_hash BINARY(32) NOT NULL,
    salt BINARY(16) NOT NULL,
    public_key VARBINARY(2048),
    encrypted_private_key VARBINARY(4096)
);

-- Legacy installations created before RSA key support lack these columns.
ALTER TABLE USERS ADD COLUMN IF NOT EXISTS public_key VARBINARY(2048);
ALTER TABLE USERS ADD COLUMN IF NOT EXISTS encrypted_private_key VARBINARY(4096);

-- ─── FILES ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS FILES (
    id VARCHAR(36) PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    owner VARCHAR(255) NOT NULL,
    original_size BIGINT NOT NULL,
    stored_size BIGINT NOT NULL,
    timestamp BIGINT NOT NULL,
    encrypted_fek VARBINARY(512),
    CONSTRAINT fk_files_owner FOREIGN KEY (owner) REFERENCES USERS(username)
);

-- Legacy installations created before per-file FEKs lack this column.
ALTER TABLE FILES ADD COLUMN IF NOT EXISTS encrypted_fek VARBINARY(512);

-- ─── SHARES ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS SHARES (
    id VARCHAR(36) PRIMARY KEY,
    file_id VARCHAR(36) NOT NULL,
    from_user VARCHAR(255) NOT NULL,
    to_user VARCHAR(255) NOT NULL,
    wrapped_fek VARBINARY(512) NOT NULL,
    timestamp BIGINT NOT NULL,
    CONSTRAINT fk_shares_file FOREIGN KEY (file_id) REFERENCES FILES(id),
    CONSTRAINT fk_shares_from_user FOREIGN KEY (from_user) REFERENCES USERS(username),
    CONSTRAINT fk_shares_to_user FOREIGN KEY (to_user) REFERENCES USERS(username)
);

-- ─── Secondary indexes for hot lookup paths ─────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_files_owner ON FILES(owner);
CREATE INDEX IF NOT EXISTS idx_shares_to_user ON SHARES(to_user);
CREATE INDEX IF NOT EXISTS idx_shares_file_id ON SHARES(file_id);
