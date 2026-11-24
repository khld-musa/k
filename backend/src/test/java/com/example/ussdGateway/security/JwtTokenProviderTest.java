package com.example.ussdGateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private final String testSecret = "mySuperSecretKeyThatMustBeAtLeast32BytesLong123!!";
    private final long testExpiration = 3600000; // 1 hour
    private final long testRefreshExpiration = 86400000; // 24 hours

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret", testSecret);
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpirationMs", testExpiration);
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtRefreshExpirationMs", testRefreshExpiration);
        jwtTokenProvider.validateSecret();
    }

    @Test
    void generateToken_Success() {
        String token = jwtTokenProvider.generateToken("testUser", "ADMIN");
        
        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));
        assertEquals("testUser", jwtTokenProvider.getUsernameFromToken(token));
    }

    @Test
    void generateRefreshToken_Success() {
        String refreshToken = jwtTokenProvider.generateRefreshToken("testUser");
        
        assertNotNull(refreshToken);
        assertTrue(jwtTokenProvider.validateToken(refreshToken));
        assertEquals("testUser", jwtTokenProvider.getUsernameFromToken(refreshToken));
    }

    @Test
    void validateToken_InvalidToken_ReturnsFalse() {
        assertFalse(jwtTokenProvider.validateToken("invalid.token.here"));
    }

    @Test
    void validateToken_NullToken_ReturnsFalse() {
        assertFalse(jwtTokenProvider.validateToken(null));
        assertFalse(jwtTokenProvider.validateToken(""));
    }

    @Test
    void getJtiFromToken_Success() {
        String token = jwtTokenProvider.generateToken("testUser", "ADMIN");
        String jti = jwtTokenProvider.getJtiFromToken(token);
        
        assertNotNull(jti);
        assertFalse(jti.isEmpty());
    }

    @Test
    void getRemainingTtlMs_Success() {
        String token = jwtTokenProvider.generateToken("testUser", "ADMIN");
        long ttl = jwtTokenProvider.getRemainingTtlMs(token);
        
        assertTrue(ttl > 0);
        assertTrue(ttl <= testExpiration);
    }
}
