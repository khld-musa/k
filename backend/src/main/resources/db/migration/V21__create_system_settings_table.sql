-- V21__create_system_settings_table.sql
-- Table for storing dynamic system configurations

CREATE TABLE system_settings (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(100) NOT NULL UNIQUE,
    setting_value TEXT,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE system_settings IS 'Configuration settings managed dynamically';
COMMENT ON COLUMN system_settings.setting_key IS 'Unique key for the configuration';
