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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Run with -Dpg.test=true -Dpg.url=jdbc:postgresql://localhost:5432/db (user postgres, empty password). */
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
        "spring.flyway.baseline-on-migrate=true"
})
class CsvExportPostgresIT {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CsvExportService csvExportService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exportsEveryRowExactlyOnce() throws Exception {
        int rows = Integer.getInteger("pg.rows", 250_000);
        jdbc.update("DELETE FROM ussd_transactions");
        LocalDate day = LocalDate.now().minusDays(1);
        jdbc.update("INSERT INTO ussd_transactions (call_id, msisdn, status, latency_ms, short_code, product_name,"
                + " error_code, node_ip, error_message, tibco_response, triggered_at) "
                + "SELECT 'c' || g, '2499' || (g % 90000), CASE WHEN g % 5 = 0 THEN 'FAILED' ELSE 'SUCCESS' END,"
                + " g % 900, '*' || (g % 40) || '#', 'Prod ' || (g % 25), CASE WHEN g % 5 = 0 THEN 'E' || (g % 13) END,"
                + " '10.0.0.' || (g % 3), 'message, with \"quotes\" ' || g, repeat('x', 300),"
                + " CAST(? AS TIMESTAMP) + (g % 80000) * INTERVAL '1 second' "
                + "FROM generate_series(1, ?) g", java.sql.Timestamp.valueOf(day.atStartOfDay()), rows);

        String d = day.toString();
        var filter = CsvExportService.UssdFilter.of(d, d, null, null, null, null, null, null, null);
        var summary = ReportSummaryResponse.builder().startDate(d).endDate(d).totalTransactions(rows)
                .dailyBreakdown(java.util.List.of()).nodeBreakdown(java.util.List.of())
                .topErrorCodes(java.util.List.of()).topShortCodes(java.util.List.of())
                .topPackages(java.util.List.of()).build();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long t0 = System.currentTimeMillis();
        long written = csvExportService.exportGeneral(bos, "data", summary, filter, n -> { });
        System.out.println("export ms=" + (System.currentTimeMillis() - t0) + " zip bytes=" + bos.size());
        assertEquals(rows, written);

        long records = 0;
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(bos.toByteArray()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals("data.csv")) {
                    InputStream in = zin;
                    byte[] buf = new byte[1 << 16];
                    int n;
                    long lines = 0;
                    while ((n = in.read(buf)) > 0) {
                        for (int i = 0; i < n; i++) if (buf[i] == '\n') lines++;
                    }
                    records = lines - 1; // header
                }
            }
        }
        assertEquals(rows, records);
        jdbc.update("DELETE FROM ussd_transactions");
    }
}
