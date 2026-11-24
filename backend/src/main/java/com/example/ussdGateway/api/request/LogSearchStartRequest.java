package com.example.ussdGateway.api.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * [FIX SEC-2] Request body for the /logs/search/start endpoint.
 * Credentials are received here once and stored in Redis; subsequent search
 * requests only carry the opaque sessionId (no password).
 *
 * When useStoredCredentials=true, password may be omitted and the backend
 * will resolve the SSH password from the DB using the provided nodeId.
 */
@Data
public class LogSearchStartRequest {

    @NotBlank(message = "SSH username is required")
    private String username;

    /** Raw SSH password — required unless useStoredCredentials is true. */
    private String password;

    /**
     * When true, the backend resolves the SSH password from the encrypted DB
     * entry for nodeId instead of using the supplied password field.
     */
    private boolean useStoredCredentials = false;

    /** Required when useStoredCredentials=true — the server ID to look up credentials for. */
    private String nodeId;
}
