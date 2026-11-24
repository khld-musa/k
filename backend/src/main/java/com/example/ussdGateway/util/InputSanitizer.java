package com.example.ussdGateway.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Input sanitization utility to prevent command injection and validate
 * user-supplied values before they reach shell commands or sensitive operations.
 */
public final class InputSanitizer {

    private static final Pattern MSISDN_PATTERN = Pattern.compile("^\\+?\\d{7,15}$");
    private static final Pattern IP_ADDRESS_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");
    private static final Pattern SAFE_SHELL_PATTERN = Pattern.compile("^[a-zA-Z0-9+_./-]+$");

    // [FIX CC-1/CC-2] Maximum limits to prevent ReDoS and resource exhaustion
    private static final int MAX_MSISDN_COUNT = 50;
    private static final int MAX_NODE_COUNT = 10;

    private InputSanitizer() {
        // Utility class
    }

    /**
     * Validate an MSISDN (phone number). Only digits allowed, optionally prefixed with '+'.
     * Length: 7–15 digits.
     *
     * @throws IllegalArgumentException if the MSISDN is invalid
     */
    public static String validateMsisdn(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            throw new IllegalArgumentException("MSISDN must not be blank");
        }
        String trimmed = msisdn.trim();
        if (!MSISDN_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid MSISDN format: " + trimmed
                    + ". Only digits (7-15) optionally prefixed with '+' are allowed.");
        }
        return trimmed;
    }

    /**
     * Validate a list of MSISDNs.
     *
     * @throws IllegalArgumentException if any MSISDN is invalid or the list is empty
     */
    public static void validateMsisdns(List<String> msisdns) {
        if (msisdns == null || msisdns.isEmpty()) {
            throw new IllegalArgumentException("At least one MSISDN is required");
        }
        // [FIX CC-1] Prevent excessive MSISDNs that could cause ReDoS on remote grep
        if (msisdns.size() > MAX_MSISDN_COUNT) {
            throw new IllegalArgumentException("Maximum " + MAX_MSISDN_COUNT + " MSISDNs allowed per request");
        }
        for (String msisdn : msisdns) {
            validateMsisdn(msisdn);
        }
    }

    /**
     * Validate a date string in ISO format (yyyy-MM-dd).
     *
     * @throws IllegalArgumentException if the date is invalid
     */
    public static LocalDate validateDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("Date must not be blank");
        }
        try {
            return LocalDate.parse(date.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format: '" + date
                    + "'. Expected yyyy-MM-dd.");
        }
    }

    /**
     * Validate an IPv4 address.
     *
     * @throws IllegalArgumentException if the IP is invalid
     */
    public static String validateIpAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("IP address must not be blank");
        }
        String trimmed = ip.trim();
        if (!IP_ADDRESS_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid IP address: " + trimmed);
        }
        return trimmed;
    }

    /**
     * Validate a node map (serverId → serverIp). All IPs must be valid.
     *
     * @throws IllegalArgumentException if any IP is invalid or the map is empty
     */
    public static void validateNodes(Map<String, String> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("At least one server node is required");
        }
        // [FIX CC-2] Prevent selecting too many nodes simultaneously
        if (nodes.size() > MAX_NODE_COUNT) {
            throw new IllegalArgumentException("Maximum " + MAX_NODE_COUNT + " nodes allowed per request");
        }
        for (Map.Entry<String, String> entry : nodes.entrySet()) {
            validateIpAddress(entry.getValue());
        }
    }

    /**
     * Escape a string for safe inclusion inside single quotes in a shell command.
     * The strategy: replace every single quote with the sequence: '"'"'
     * which ends the single-quoted string, adds a double-quoted single quote,
     * and re-opens the single-quoted string.
     */
    public static String shellEscape(String value) {
        if (value == null) {
            return "";
        }
        // Reject any value containing control characters or null bytes
        for (char c : value.toCharArray()) {
            if (c < 0x20 && c != '\t') {
                throw new IllegalArgumentException("Input contains invalid control characters");
            }
        }
        return value.replace("'", "'\"'\"'");
    }

    /**
     * Validate that a value contains only safe characters for use in a shell context.
     * Allowed: alphanumerics, +, _, ., /, -
     *
     * @throws IllegalArgumentException if the value contains unsafe characters
     */
    public static String validateSafeShellValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value must not be blank");
        }
        String trimmed = value.trim();
        if (!SAFE_SHELL_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Value contains unsafe characters: " + trimmed);
        }
        return trimmed;
    }

    /**
     * Validate username for SSH (basic check — no shell metacharacters).
     */
    public static String validateSshUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("SSH username must not be blank");
        }
        String trimmed = username.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("SSH username too long");
        }
        // Allow alphanumerics, dots, underscores, hyphens
        if (!trimmed.matches("^[a-zA-Z0-9._-]+$")) {
            throw new IllegalArgumentException("SSH username contains invalid characters");
        }
        return trimmed;
    }
}

