package com.example.ussdGateway.config;

import com.example.ussdGateway.entity.User;
import com.example.ussdGateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

/**
 * Data Initializer
 * Seeds initial admin user on first startup.
 * Admin password MUST be provided via ADMIN_INITIAL_PASSWORD environment variable.
 * Server seeding is only performed in the "dev" profile.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final com.example.ussdGateway.repository.UssdServerRepository ussdServerRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Value("${admin.initial-password:}")
    private String adminInitialPassword;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        seedAdminUser();

        // Only seed server data in "dev" profile — production servers must be managed via the UI
        if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
            seedServerData();
        }
    }

    private void seedServerData() {
        if (ussdServerRepository.count() == 0) {
            log.info("DEV profile: Seeding sample USSD servers...");

            String[][] sampleServers = {
                {"Server 1", "127.0.0.1", "Sample Dev Node 1"},
                {"Server 2", "127.0.0.1", "Sample Dev Node 2"},
            };

            for (String[] s : sampleServers) {
                com.example.ussdGateway.entity.UssdServer server = new com.example.ussdGateway.entity.UssdServer();
                server.setName(s[0]);
                server.setIpAddress(s[1]);
                server.setDescription(s[2]);
                server.setActive(true);
                ussdServerRepository.save(server);
            }
            log.info("Sample USSD servers seeded (dev only).");
        }
    }

    private void seedAdminUser() {
        String adminUsername = "admin";
        if (!userRepository.existsByUsername(adminUsername)) {
            if (adminInitialPassword == null || adminInitialPassword.isBlank() || adminInitialPassword.equals("admin123") || adminInitialPassword.toLowerCase().contains("changeme")) {
                boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
                if (isProd) {
                    log.error("==========================================================");
                    log.error("INSECURE ADMIN_INITIAL_PASSWORD DETECTED.");
                    log.error("Cannot create admin user in production with missing or default/weak password.");
                    log.error("Set a strong ADMIN_INITIAL_PASSWORD (not 'admin123') and restart.");
                    log.error("==========================================================");
                    throw new IllegalStateException("Insecure ADMIN_INITIAL_PASSWORD in production");
                }
                // Dev fallback — use a temporary password but still force change
                adminInitialPassword = "ChangeMeOnFirstLogin!1";
                log.warn("No secure ADMIN_INITIAL_PASSWORD set. Using temporary dev password. User will be forced to change it.");
            }

            log.info("Creating default admin user (password change will be required on first login)...");

            User admin = new User();
            admin.setUsername(adminUsername);
            admin.setPasswordHash(passwordEncoder.encode(adminInitialPassword));
            admin.setEmail("admin@kraken.local");
            admin.setRole(User.Role.ADMIN);
            admin.setActive(true);
            admin.setMustChangePassword(true);

            userRepository.save(admin);
            log.info("Default admin user created. Password change required on first login.");
        } else {
            log.info("Admin user already exists. Skipping seeder.");
        }
    }
}
