package com.example.ussdGateway.api.response;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;

/**
 * Lightweight dashboard summary — accessible to ALL authenticated users.
 * Contains platform activity stats (searches, traces) and system info.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryResponse {

    private LocalDate dataDate;

    // ── Active feature stats ──
    private long totalNodes;
    private long searchesToday;
    private long tracesToday;
}
