package com.example.ussdGateway.util;

import com.example.ussdGateway.entity.UssdServer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Resolves the local on-disk directory for a server's USSD log files, shared between
 * {@code LogFetchService} (which writes files there) and {@code LogIngestionService}
 * (which reads them).
 * <p>
 * Ensures files are saved and read directly under {@code /mediation/SUB1}, {@code /mediation/SUB2},
 * and {@code /mediation/SUB3}.
 */
public final class ServerLogPaths {

    public static final String DEFAULT_BASE_PATH = "/mediation";

    private ServerLogPaths() {
        // Utility class
    }

    /**
     * Resolves the local on-disk directory for a server's USSD log files.
     * Guaranteed to prioritize /mediation/SUB1, /mediation/SUB2, /mediation/SUB3 etc.
     */
    public static Path localDir(UssdServer server) {
        return resolveServerDir(server, DEFAULT_BASE_PATH);
    }

    /**
     * Resolves and ensures the local log directory for the given server.
     * Standardizes server names (e.g. "sub 1" -> "SUB1", "sub 2" -> "SUB2", "sub 3" -> "SUB3").
     */
    public static Path resolveServerDir(UssdServer server, String configuredBasePath) {
        String serverName = server != null && server.getName() != null ? server.getName().trim() : "";
        String normalizedServerName = serverName.replaceAll("\\s+", "");

        // Candidate base paths: configuredBasePath (defaults to /mediation), then /mediation explicitly, then server.getLocalLogPath()
        List<String> basePaths = new ArrayList<>();
        if (configuredBasePath != null && !configuredBasePath.isBlank()) {
            basePaths.add(configuredBasePath.trim());
        }
        if (!basePaths.contains(DEFAULT_BASE_PATH)) {
            basePaths.add(DEFAULT_BASE_PATH);
        }
        if (server != null && server.getLocalLogPath() != null && !server.getLocalLogPath().isBlank()) {
            String serverLocalPath = server.getLocalLogPath().trim();
            if (!basePaths.contains(serverLocalPath)) {
                basePaths.add(serverLocalPath);
            }
        }

        for (String bp : basePaths) {
            Path baseDir = Paths.get(bp);
            if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
                continue;
            }

            // 1. Check uppercase normalized (e.g. /mediation/SUB1, /mediation/SUB2, /mediation/SUB3)
            Path pUpper = baseDir.resolve(normalizedServerName.toUpperCase());
            if (Files.exists(pUpper) && Files.isDirectory(pUpper)) {
                return pUpper;
            }

            // 2. Check exact serverName (e.g. /mediation/SUB1)
            Path p1 = baseDir.resolve(serverName);
            if (Files.exists(p1) && Files.isDirectory(p1)) {
                return p1;
            }

            // 3. Check lowercase normalized (e.g. /mediation/sub1)
            Path pLower = baseDir.resolve(normalizedServerName.toLowerCase());
            if (Files.exists(pLower) && Files.isDirectory(pLower)) {
                return pLower;
            }

            // 4. Check spaced uppercase, e.g. "sub 1" <-> "SUB 1"
            String spaced = normalizedServerName.replaceAll("(?i)([a-zA-Z]+)(\\d+)", "$1 $2");
            Path pSpaced = baseDir.resolve(spaced.toUpperCase());
            if (Files.exists(pSpaced) && Files.isDirectory(pSpaced)) {
                return pSpaced;
            }

            // 5. Case-insensitive scan of subdirectories inside baseDir
            try (Stream<Path> subDirs = Files.list(baseDir)) {
                Optional<Path> match = subDirs
                        .filter(Files::isDirectory)
                        .filter(d -> {
                            String dirName = d.getFileName().toString().replaceAll("\\s+", "");
                            return dirName.equalsIgnoreCase(normalizedServerName);
                        })
                        .findFirst();
                if (match.isPresent()) {
                    return match.get();
                }
            } catch (IOException ignored) {}

            // 6. Check base directory directly if it has TIBCO-*.log files
            try (Stream<Path> files = Files.list(baseDir)) {
                boolean hasLogFiles = files.anyMatch(f -> f.getFileName().toString().startsWith("TIBCO-"));
                if (hasLogFiles) {
                    return baseDir;
                }
            } catch (IOException ignored) {}
        }

        // If none exists on disk yet, default to /mediation/{SUB1, SUB2, SUB3} and ensure directory exists
        String targetBase = (configuredBasePath != null && !configuredBasePath.isBlank()) ? configuredBasePath : DEFAULT_BASE_PATH;
        Path defaultDir = Paths.get(targetBase, normalizedServerName.toUpperCase());
        try {
            Files.createDirectories(defaultDir);
            File f = defaultDir.toFile();
            f.setReadable(true, false);
            f.setWritable(true, false);
            f.setExecutable(true, false);
        } catch (Exception ignored) {}
        return defaultDir;
    }
}
