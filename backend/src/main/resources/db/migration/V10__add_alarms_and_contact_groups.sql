CREATE TABLE contact_groups (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE contacts (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone_number VARCHAR(255),
    group_id BIGINT NOT NULL,
    FOREIGN KEY (group_id) REFERENCES contact_groups(id) ON DELETE CASCADE
);

CREATE TABLE alarm_configs (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255),
    client_id VARCHAR(255) NOT NULL,
    api_key VARCHAR(512) NOT NULL,
    minimum_threshold NUMERIC(19, 2) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_alert_sent_at TIMESTAMP
);

CREATE TABLE alarm_group_subscriptions (
    alarm_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    PRIMARY KEY (alarm_id, group_id),
    FOREIGN KEY (alarm_id) REFERENCES alarm_configs(id) ON DELETE CASCADE,
    FOREIGN KEY (group_id) REFERENCES contact_groups(id) ON DELETE CASCADE
);
