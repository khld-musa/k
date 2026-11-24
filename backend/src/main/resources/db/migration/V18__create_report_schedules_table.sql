-- Report Scheduling & Distribution
CREATE TABLE report_schedules (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    frequency       VARCHAR(20)  NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    day_of_week     VARCHAR(10),           -- e.g. 'MONDAY' (used when frequency=WEEKLY)
    day_of_month    INTEGER,               -- 1-28 (used when frequency=MONTHLY)
    time_of_day     VARCHAR(5)   NOT NULL,  -- 'HH:mm' format, server-local time
    range_days      INTEGER      NOT NULL DEFAULT 1,  -- how many days back the report covers
    node_ip         VARCHAR(45),           -- null = ALL nodes
    contact_group_ids TEXT       NOT NULL,  -- comma-separated group IDs, e.g. '1,3,5'
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at     TIMESTAMP,
    next_run_at     TIMESTAMP    NOT NULL,
    created_by      VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_report_schedules_active_next ON report_schedules (is_active, next_run_at);

