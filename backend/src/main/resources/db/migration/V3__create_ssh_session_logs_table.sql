-- V3__create_ssh_session_logs_table.sql
-- Table for tracking SSH terminal sessions

CREATE TABLE ssh_session_logs (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    target_host VARCHAR(255) NOT NULL,
    target_user VARCHAR(50) NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    error_message TEXT
);

CREATE INDEX idx_ssh_session_logs_username ON ssh_session_logs(username);
CREATE INDEX idx_ssh_session_logs_start_time ON ssh_session_logs(start_time);
CREATE INDEX idx_ssh_session_logs_status ON ssh_session_logs(status);

COMMENT ON TABLE ssh_session_logs IS 'Tracks SSH terminal sessions initiated from the web portal';
