-- V25__create_mcn_servers_table.sql
-- Table for managing MCN (SIP / NOTIFIER) cluster nodes used by the MCN log checker

CREATE TABLE mcn_servers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    ip_address VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    log_path VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    server_type VARCHAR(20) NOT NULL,
    ssh_username VARCHAR(100),
    encrypted_password VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_mcn_servers_active ON mcn_servers(active);
CREATE INDEX idx_mcn_servers_server_type ON mcn_servers(server_type);

COMMENT ON TABLE mcn_servers IS 'MCN SIP/NOTIFIER backend servers configuration used by the MCN log checker';
