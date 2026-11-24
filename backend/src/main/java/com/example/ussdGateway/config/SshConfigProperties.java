package com.example.ussdGateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Centralized SSH configuration for all services that establish SSH connections.
 * Controls host key verification behavior and optional known_hosts file.
 *
 * In production, set SSH_STRICT_HOST_KEY_CHECKING=yes and provide SSH_KNOWN_HOSTS_FILE
 * to prevent MITM attacks.
 */
@Slf4j
@Getter
@Configuration
public class SshConfigProperties {

    @Value("${ssh.strict-host-key-checking:no}")
    private String strictHostKeyChecking;

    @Value("${ssh.known-hosts-file:}")
    private String knownHostsFile;

    @Autowired
    private Environment env;

    @PostConstruct
    public void validate() {
        if (hasKnownHostsFile()) {
            try {
                java.io.File file = new java.io.File(knownHostsFile);
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                if (!file.exists()) {
                    file.createNewFile();
                    log.info("Created empty known_hosts file: {}", file.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn("Could not create known_hosts file at {}: {}", knownHostsFile, e.getMessage());
            }
        }

        if ("no".equalsIgnoreCase(strictHostKeyChecking)) {
            if (java.util.Arrays.asList(env.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException("CRITICAL SECURITY ERROR: SSH StrictHostKeyChecking MUST NOT be 'no' in 'prod' profile. Use 'accept-new' or 'yes' instead.");
            }
            log.warn("==========================================================");
            log.warn("SSH StrictHostKeyChecking is DISABLED.");
            log.warn("This allows connections to any host without verifying its");
            log.warn("identity, enabling potential man-in-the-middle attacks.");
            log.warn("For production, set SSH_STRICT_HOST_KEY_CHECKING=accept-new or yes.");
            log.warn("==========================================================");
        } else {
            log.info("SSH StrictHostKeyChecking set to '{}'. Known hosts file: {}",
                    strictHostKeyChecking,
                    (knownHostsFile != null && !knownHostsFile.isBlank()) ? knownHostsFile : "(not configured)");
        }
    }

    /**
     * @return true if a known hosts file path is configured and non-blank
     */
    public boolean hasKnownHostsFile() {
        return knownHostsFile != null && !knownHostsFile.isBlank();
    }

    /**
     * Returns a StrictHostKeyChecking value that JSch actually understands.
     *
     * JSch only supports: "yes", "no", "ask".
     * OpenSSH's "accept-new" (trust new hosts, reject changed keys) is NOT a valid JSch value.
     * When JSch receives an unknown value it silently falls back to strict ("yes") behaviour,
     * which causes "reject HostKey" for every host not already in the known_hosts file.
     *
     * Mapping applied here:
     *   "accept-new" → "no"  — JSch will auto-add unknown hosts to the known_hosts file
     *                           and will still reject hosts whose key HAS CHANGED (MITM protection).
     *   "yes" / "no" / "ask" → returned as-is.
     *
     * NOTE: JSch's "no" does NOT mean "ignore key changes" — it only means "auto-accept new hosts".
     * A key mismatch for an already-known host is still rejected by JSch regardless of this setting.
     */
    public String toJschValue() {
        if ("accept-new".equalsIgnoreCase(strictHostKeyChecking)) {
            return "no";
        }
        return strictHostKeyChecking;
    }
}
