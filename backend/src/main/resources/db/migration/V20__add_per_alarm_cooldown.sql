-- Per-alarm configurable alert cooldown (replaces global 6-hour hardcode)
ALTER TABLE alarm_configs
    ADD COLUMN cooldown_minutes INTEGER NOT NULL DEFAULT 360;

