package com.example.ussdGateway.service;

import com.example.ussdGateway.api.response.ReportSummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares rollup-backed summaries with raw-table summaries on a real PostgreSQL.
 * Run with: -Dpg.test=true -Dpg.url=jdbc:postgresql://localhost:5432/db (user postgres, empty password).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "pg.test", matches = "true")
@TestPropertySource(properties = {
        "spring.datasource.url=${pg.url}",
        "spring.datasource.driverClassName=org.postgresql.Driver",
        "spring.datasource.username=postgres",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=true",
        "app.reports.rollup.enabled=true"
})
class ReportRollupPostgresIT {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ReportService reportService;
    @Autowired
    private ReportRollupService rollupService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rollupSummaryMatchesRawSummary() {
        int rows = Integer.getInteger("pg.rows", 200_000);
        jdbc.update("DELETE FROM ussd_transactions");
        jdbc.update("DELETE FROM ussd_stats_hourly");
        jdbc.update("DELETE FROM ussd_stats_hourly_coverage");
        LocalDate day = LocalDate.now().minusDays(1);
        jdbc.update("INSERT INTO ussd_transactions (call_id, msisdn, status, latency_ms, short_code, product_name,"
                + " error_code, node_ip, error_message, triggered_at) "
                + "SELECT 'c' || g, '2499' || (g % 90000), CASE WHEN g % 5 = 0 THEN 'FAILED' ELSE 'SUCCESS' END,"
                + " CASE WHEN g % 7 = 0 THEN NULL ELSE g % 900 END,"
                + " CASE WHEN g % 11 = 0 THEN NULL ELSE '*' || (g % 40) || '#' END, 'Prod ' || (g % 25),"
                + " CASE WHEN g % 5 = 0 THEN 'E' || (g % 13) END, '10.0.0.' || (g % 3),"
                + " CASE WHEN g % 5 = 0 THEN 'msg ' || (g % 13) END,"
                + " CAST(? AS TIMESTAMP) + (g * INTERVAL '1 second') * (86000.0 / ?) "
                + "FROM generate_series(1, ?) g", java.sql.Timestamp.valueOf(day.atStartOfDay()), rows, rows);

        String d = day.toString();
        // raw: msisdn filter forces the raw path; compare against empty-filter rollup path using equal data
        long t0 = System.currentTimeMillis();
        rollupService.rebuildDays(day, day);
        System.out.println("rollup build ms: " + (System.currentTimeMillis() - t0));

        for (String[] f : new String[][]{
                {null, null, null, null}, {"10.0.0.1", null, null, null}, {null, "FAILED", null, null},
                {null, null, "*1", null}, {null, null, null, "e1"}}) {
            rollupService.rebuildDays(day, day); // no-op refresh, keeps coverage
            ReportSummaryResponse viaRollup = reportService.getSummary(d, d, f[0], f[1], f[2], null, null, f[3], null);
            // "%%"-style message filter that matches everything forces the raw path
            ReportSummaryResponse raw = reportService.getSummary(d, d, f[0], f[1], f[2], null, "2499", f[3], null);
            if (f[3] == null && f[1] == null) {
                assertTrue(viaRollup.getTotalTransactions() > 0);
            }
            // raw path with msisdn 2499 matches every row, so results must be identical
            assertEquals(raw.getTotalTransactions(), viaRollup.getTotalTransactions());
            assertEquals(raw.getSuccessCount(), viaRollup.getSuccessCount());
            assertEquals(raw.getAverageLatencyMs(), viaRollup.getAverageLatencyMs(), 1e-6);
            assertEquals(raw.getNodeBreakdown().size(), viaRollup.getNodeBreakdown().size());
            assertEquals(raw.getTopErrorCodes().size(), viaRollup.getTopErrorCodes().size());
            assertEquals(raw.getTopShortCodes().size(), viaRollup.getTopShortCodes().size());
            assertEquals(raw.getTopPackages().size(), viaRollup.getTopPackages().size());
            for (int i = 0; i < raw.getTopShortCodes().size(); i++) {
                assertEquals(raw.getTopShortCodes().get(i).getTotal(), viaRollup.getTopShortCodes().get(i).getTotal());
            }
        }

        long a = System.currentTimeMillis();
        reportService.getSummary(d, d, null, null, null, null, "249", null, null);
        long b = System.currentTimeMillis();
        reportService.getSummary(d, d, null, null, null, null, null, null, "msg");
        long c = System.currentTimeMillis();
        reportService.getSummary(d, d, null, "FAILED", null, null, null, null, null);
        long e = System.currentTimeMillis();
        System.out.println("raw ms=" + (b - a) + " rawMsg ms=" + (c - b) + " rollup ms=" + (e - c));
        jdbc.update("DELETE FROM ussd_transactions");
    }
}
