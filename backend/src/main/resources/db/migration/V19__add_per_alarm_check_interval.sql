-- Per-alarm configurable check interval (replaces global 30-min hardcode)
ALTER TABLE alarm_configs
    ADD COLUMN check_interval_minutes INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN last_checked_at        TIMESTAMP;

