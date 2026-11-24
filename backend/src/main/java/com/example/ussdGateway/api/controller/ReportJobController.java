package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.request.ReportJobRequestDto;
import com.example.ussdGateway.api.dto.response.ReportJobDto;
import com.example.ussdGateway.service.DownloadTicketService;
import com.example.ussdGateway.service.ReportJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/reports/jobs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
public class ReportJobController {

    private final ReportJobService reportJobService;
    private final DownloadTicketService downloadTicketService;

    /**
     * Request a new background report export. Returns immediately with QUEUED status.
     */
    @PostMapping
    public ResponseEntity<ReportJobDto> createJob(
            @Valid @RequestBody ReportJobRequestDto request,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        return ResponseEntity.ok(reportJobService.createJob(request, username));
    }

    /**
     * Poll status of a specific job.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ReportJobDto> getJob(
            @PathVariable UUID id,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        return ResponseEntity.ok(reportJobService.getJob(id, username));
    }

    /**
     * Check if there is an active or recently completed job for this user and report type.
     */
    @GetMapping("/latest")
    public ResponseEntity<ReportJobDto> getLatestJob(
            @RequestParam String reportType,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        return ResponseEntity.of(reportJobService.getLatestJob(reportType, username));
    }

    /**
     * List recent jobs requested by current user.
     */
    @GetMapping
    public ResponseEntity<List<ReportJobDto>> getUserRecentJobs(Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        return ResponseEntity.ok(reportJobService.getUserRecentJobs(username));
    }

    /**
     * Issue a short-lived ticket allowing a native browser download of this job's file.
     */
    @PostMapping("/{id}/download-ticket")
    public ResponseEntity<Map<String, Object>> issueDownloadTicket(
            @PathVariable UUID id,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        reportJobService.getJob(id, username); // validates existence and ownership
        String authority = auth != null && !auth.getAuthorities().isEmpty()
                ? auth.getAuthorities().iterator().next().getAuthority() : "ROLE_VIEWER";
        String ticket = downloadTicketService.issue(id.toString(), username, authority);
        return ResponseEntity.ok(Map.of("ticket", ticket, "expiresInSeconds", downloadTicketService.ttlSeconds()));
    }

    /**
     * Download the completed report file (supports HTTP Range for resuming).
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadReport(
            @PathVariable UUID id,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        ReportJobService.DownloadableFile download = reportJobService.getDownloadFile(id, username);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(download.resource());
    }

    /**
     * Cancel an active or queued report job.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelJob(
            @PathVariable UUID id,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        reportJobService.cancelJob(id, username);
        return ResponseEntity.noContent().build();
    }
}
