-- V7__create_ussd_servers_table.sql
-- Table for managing USSD backend nodes

CREATE TABLE ussd_servers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    ip_address VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ussd_servers_active ON ussd_servers(active);
CREATE INDEX idx_ussd_servers_ip ON ussd_servers(ip_address);

COMMENT ON TABLE ussd_servers IS 'USSD backend servers configuration';
