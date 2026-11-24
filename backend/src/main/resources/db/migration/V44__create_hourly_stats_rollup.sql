-- Hourly pre-aggregated statistics so report summaries never have to scan raw ussd_transactions.
-- Dimensions use '' instead of NULL so they can be part of the primary key.
CREATE TABLE IF NOT EXISTS ussd_stats_hourly (
    hour_start     TIMESTAMP     NOT NULL,
    node_ip        VARCHAR(255)  NOT NULL DEFAULT '',
    status         VARCHAR(50)   NOT NULL DEFAULT '',
    short_code     VARCHAR(255)  NOT NULL DEFAULT '',
    product_name   VARCHAR(255)  NOT NULL DEFAULT '',
    error_code     VARCHAR(255)  NOT NULL DEFAULT '',
    total          BIGINT        NOT NULL,
    success        BIGINT        NOT NULL,
    latency_sum    BIGINT        NOT NULL DEFAULT 0,
    latency_count  BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (hour_start, node_ip, status, short_code, product_name, error_code)
);

-- One row per hour that has been (re)computed, including hours with no traffic.
CREATE TABLE IF NOT EXISTS ussd_stats_hourly_coverage (
    hour_start   TIMESTAMP PRIMARY KEY,
    computed_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
