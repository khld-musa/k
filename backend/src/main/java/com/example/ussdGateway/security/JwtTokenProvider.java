package com.example.ussdGateway.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration}")
    private long jwtExpirationMs;

    @Value("${jwt.refresh-expiration}")
    private long jwtRefreshExpirationMs;

    @PostConstruct
    public void validateSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT secret is not configured. Set JWT_SECRET environment variable.");
        }
        if (jwtSecret.contains("changeme") || jwtSecret.contains("change-in-production") || jwtSecret.contains("your-256-bit") || jwtSecret.contains("placeholder")) {
            throw new IllegalStateException(
                    "JWT secret contains a default placeholder value ('changeme' etc). "
                    + "Set a strong, unique JWT_SECRET environment variable (min 32 characters).");
        }
        if (jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "JWT secret is too short (must be at least 32 characters). "
                    + "Set a strong JWT_SECRET environment variable.");
        }
        log.info("JWT secret validated successfully.");
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtRefreshExpirationMs);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    private Claims parseClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getUsernameFromToken(String token) {
        return parseClaimsFromToken(token).getSubject();
    }

    public String getRoleFromToken(String token) {
        return parseClaimsFromToken(token).get("role", String.class);
    }

    public String getJtiFromToken(String token) {
        return parseClaimsFromToken(token).getId();
    }

    /**
     * Returns the remaining time-to-live for the token in milliseconds.
     */
    public long getRemainingTtlMs(String token) {
        Date expiration = parseClaimsFromToken(token).getExpiration();
        long remaining = expiration.getTime() - System.currentTimeMillis();
        return Math.max(remaining, 0);
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getExpirationTime() {
        return jwtExpirationMs;
    }
}
