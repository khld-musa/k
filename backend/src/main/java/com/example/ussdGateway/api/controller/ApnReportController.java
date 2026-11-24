package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.ApnRequestDto;
import com.example.ussdGateway.api.dto.response.ApnReportSummaryDto;
import com.example.ussdGateway.service.ApnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/reports/apn")
@RequiredArgsConstructor
@Tag(name = "APN Reports", description = "Reports and metrics for external APN traffic")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
public class ApnReportController {

    private final ApnService apnService;

    @Operation(summary = "Get APN summary report", description = "Returns totals, success rate, throttled count, latency, and daily trends")
    @GetMapping("/summary")
    public ResponseEntity<ApnReportSummaryDto> getSummary(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(apnService.getSummary(startDate, endDate));
    }

    @Operation(summary = "Get paginated APN transactions", description = "Filterable transactions by MSISDN, IMSI, status, and date range")
    @GetMapping("/transactions")
    public ResponseEntity<Page<ApnRequestDto>> getTransactions(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String msisdn,
            @RequestParam(required = false) String imsi,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(apnService.getTransactions(startDate, endDate, status, msisdn, imsi, page, size));
    }
}
