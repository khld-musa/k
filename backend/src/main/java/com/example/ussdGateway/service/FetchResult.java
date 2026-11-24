package com.example.ussdGateway.service;

/**
 * Outcome of {@link LogFetchService}'s per-server FTP pull, surfaced back through the manual
 * "run now" endpoint so an admin can see exactly what happened (or didn't) without needing log
 * access - e.g. "no FTP credentials configured" instead of a generic success message that hides
 * a silent no-op.
 */
public record FetchResult(String serverName, Status status, int filesFetched, String message) {

    public enum Status {
        /** At least one new log file was pulled and deleted remotely. */
        FETCHED,
        /** Ran fine but there was nothing new to pull. */
        NO_NEW_FILES,
        /** Not attempted - missing FTP credentials/path/local dir configuration. */
        SKIPPED,
        /** Attempted but failed - connection, login, or transfer error. */
        ERROR
    }
}
