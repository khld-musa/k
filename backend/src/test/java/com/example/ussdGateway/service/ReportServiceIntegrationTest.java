package com.example.ussdGateway.service;

import com.example.ussdGateway.api.response.ReportSummaryResponse;
import com.example.ussdGateway.entity.UssdTransaction;
import com.example.ussdGateway.repository.UssdTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportServiceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ReportService reportService;

    @Autowired
    private UssdTransactionRepository ussdTransactionRepository;

    @BeforeEach
    void setUp() {
        ussdTransactionRepository.deleteAll();

        UssdTransaction tx1 = new UssdTransaction();
        tx1.setCallId("test-call-1");
        tx1.setMsisdn("249123456789");
        tx1.setStatus("FAILED");
        tx1.setErrorCode("ERR_504");
        tx1.setErrorMessage("Gateway Timeout");
        tx1.setShortCode("*123#");
        tx1.setProductName("Data Bundle");
        tx1.setNodeIp("10.0.0.1");
        tx1.setLatencyMs(1500L);
        tx1.setTriggeredAt(LocalDateTime.now().minusDays(1));
        ussdTransactionRepository.save(tx1);

        UssdTransaction tx2 = new UssdTransaction();
        tx2.setCallId("test-call-2");
        tx2.setMsisdn("249123456790");
        tx2.setStatus("SUCCESS");
        tx2.setShortCode("*123#");
        tx2.setProductName("Data Bundle");
        tx2.setNodeIp("10.0.0.2");
        tx2.setLatencyMs(450L);
        tx2.setTriggeredAt(LocalDateTime.now());
        ussdTransactionRepository.save(tx2);
    }

    @Test
    void testGetSummary_SingleDay() {
        String today = LocalDate.now().toString();
        ReportSummaryResponse summary = reportService.getSummary(
                today, today, null, null, null, null, null, null, null);

        assertNotNull(summary);
        assertEquals(1, summary.getTotalTransactions());
        assertEquals(1, summary.getSuccessCount());
        assertEquals(0, summary.getFailedCount());
        assertEquals(100.0, summary.getSuccessRatePercent());
        assertNotNull(summary.getDailyBreakdown());
        assertEquals(1, summary.getDailyBreakdown().size());
    }

    @Test
    void testGetSummary_MultiDay() {
        String start = LocalDate.now().minusDays(2).toString();
        String end = LocalDate.now().toString();
        ReportSummaryResponse summary = reportService.getSummary(
                start, end, null, null, null, null, null, null, null);

        assertNotNull(summary);
        assertEquals(2, summary.getTotalTransactions());
        assertEquals(1, summary.getSuccessCount());
        assertEquals(1, summary.getFailedCount());
        assertEquals(50.0, summary.getSuccessRatePercent());
        assertNotNull(summary.getDailyBreakdown());
        assertTrue(summary.getDailyBreakdown().size() >= 1);
    }

    @Test
    void testGetSummary_ZeroTransactions() {
        String past = LocalDate.now().minusDays(10).toString();
        ReportSummaryResponse summary = reportService.getSummary(
                past, past, null, null, null, null, null, null, null);

        assertNotNull(summary);
        assertEquals(0, summary.getTotalTransactions());
        assertEquals(0, summary.getSuccessCount());
        assertEquals(0, summary.getFailedCount());
        assertEquals(0.0, summary.getSuccessRatePercent());
        assertTrue(summary.getTopErrorCodes().isEmpty());
    }

    @Test
    void testGetSummary_FailedOnly() {
        String start = LocalDate.now().minusDays(2).toString();
        String end = LocalDate.now().toString();
        ReportSummaryResponse summary = reportService.getSummary(
                start, end, null, "FAILED", null, null, null, null, null);

        assertNotNull(summary);
        assertEquals(1, summary.getTotalTransactions());
        assertEquals(0, summary.getSuccessCount());
        assertEquals(1, summary.getFailedCount());
        assertEquals(0.0, summary.getSuccessRatePercent());
        assertEquals(1, summary.getTopErrorCodes().size());
        assertEquals("ERR_504", summary.getTopErrorCodes().get(0).getErrorCode());
    }

    @Test
    void testGetTransactions_PagingAndTotals() {
        String start = LocalDate.now().minusDays(2).toString();
        String end = LocalDate.now().toString();

        var first = reportService.getTransactions(start, end, null, null, null, null, null, null, null, 0, 1);
        assertEquals(1, first.getContent().size());
        assertEquals(2, first.getTotalElements());
        assertEquals("SUCCESS", first.getContent().get(0).getStatus());

        var second = reportService.getTransactions(start, end, null, null, null, null, null, null, null, 1, 1);
        assertEquals(2, second.getTotalElements());
        assertEquals("FAILED", second.getContent().get(0).getStatus());

        var filtered = reportService.getTransactions(start, end, null, "FAILED", "*123#", null, "err_504", null,
                "timeout", 0, 20);
        assertEquals(1, filtered.getTotalElements());
    }
}
