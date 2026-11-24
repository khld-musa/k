package com.example.ussdGateway.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            // Repair any failed migration states and reconcile checksums automatically
            flyway.repair();
            // Run all pending migrations
            flyway.migrate();
        };
    }
}
