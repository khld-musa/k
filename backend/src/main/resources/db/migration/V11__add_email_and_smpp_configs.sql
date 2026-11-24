-- Mail Server Configuration
CREATE TABLE email_configs (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    smtp_host VARCHAR(255) NOT NULL,
    smtp_port INTEGER NOT NULL DEFAULT 587,
    username VARCHAR(255) NOT NULL,
    password VARCHAR(512) NOT NULL,
    from_address VARCHAR(255) NOT NULL,
    use_tls BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

-- SMPP Account Configuration
CREATE TABLE smpp_configs (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    smpp_host VARCHAR(255) NOT NULL,
    smpp_port INTEGER NOT NULL DEFAULT 2775,
    system_id VARCHAR(255) NOT NULL,
    password VARCHAR(512) NOT NULL,
    system_type VARCHAR(255),
    source_address VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);
