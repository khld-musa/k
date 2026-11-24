package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.request.LoginRequest;
import com.example.ussdGateway.api.dto.response.LoginResponse;
import com.example.ussdGateway.entity.User;
import com.example.ussdGateway.exception.AuthenticationFailedException;
import com.example.ussdGateway.exception.ResourceNotFoundException;
import com.example.ussdGateway.repository.UserRepository;
import com.example.ussdGateway.security.JwtTokenProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditLogService auditLogService;
    private final TokenBlacklistService tokenBlacklistService;
    private final StringRedisTemplate redisTemplate;

    public AuthService(UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      JwtTokenProvider jwtTokenProvider,
                      AuditLogService auditLogService,
                      TokenBlacklistService tokenBlacklistService,
                      StringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.auditLogService = auditLogService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String rateLimitKey = "login_attempts:" + request.getUsername();
        String attemptsStr = redisTemplate.opsForValue().get(rateLimitKey);
        int attempts = attemptsStr != null ? Integer.parseInt(attemptsStr) : 0;
        
        if (attempts >= 5) {
            throw new AuthenticationFailedException("Account locked due to too many failed login attempts. Try again later.");
        }

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> {
                    recordFailedAttempt(rateLimitKey, attempts);
                    return new AuthenticationFailedException("Invalid username or password");
                });

        if (!user.isActive()) {
            recordFailedAttempt(rateLimitKey, attempts);
            throw new AuthenticationFailedException("User account is disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            recordFailedAttempt(rateLimitKey, attempts);
            throw new AuthenticationFailedException("Invalid username or password");
        }

        // Success - clear failed attempts (F-07)
        redisTemplate.delete(rateLimitKey);

        // Update last login
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        // Generate tokens
        String token = jwtTokenProvider.generateToken(user.getUsername(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getUsername());

        // Audit log
        auditLogService.log("LOGIN", user.getUsername(), null, "User logged in successfully");

        return new LoginResponse(
                token,
                refreshToken,
                jwtTokenProvider.getExpirationTime(),
                user.isMustChangePassword(),
                new LoginResponse.UserDto(user)
        );
    }

    public void logout(String token) {
        String username = "unknown";
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) username = auth.getName();
        } catch (Exception ignored) {}

        // Blacklist the current token so it can't be reused
        try {
            if (token != null && token.startsWith("Bearer ")) {
                String jwt = token.substring(7);
                if (jwtTokenProvider.validateToken(jwt)) {
                    String jti = jwtTokenProvider.getJtiFromToken(jwt);
                    long remainingMs = jwtTokenProvider.getRemainingTtlMs(jwt);
                    if (jti != null && remainingMs > 0) {
                        tokenBlacklistService.blacklist(jti, remainingMs);
                    }
                }
            }
        } catch (Exception e) {
            // Don't fail logout if blacklisting fails
        }

        auditLogService.log("LOGOUT", username, null, "User logged out");
        SecurityContextHolder.clearContext();
    }

    private void recordFailedAttempt(String key, int currentAttempts) {
        if (currentAttempts == 0) {
            redisTemplate.opsForValue().set(key, "1", 3, TimeUnit.MINUTES);
        } else {
            redisTemplate.opsForValue().increment(key);
        }
    }

    public LoginResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new AuthenticationFailedException("Invalid refresh token");
        }
        
        String usedKey = "used_refresh_token:" + refreshToken;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(usedKey))) {
            // F-08: Token reuse detected!
            String username = jwtTokenProvider.getUsernameFromToken(refreshToken);
            auditLogService.log("SECURITY_ALERT", username, null, "Refresh token reuse detected. Forcing re-authentication.");
            throw new AuthenticationFailedException("Invalid or reused refresh token. Please log in again.");
        }
        // Mark token as used for 7 days (typical refresh token lifespan)
        redisTemplate.opsForValue().set(usedKey, "used", 7, TimeUnit.DAYS);

        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));

        String newToken = jwtTokenProvider.generateToken(user.getUsername(), user.getRole().name());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getUsername());

        return new LoginResponse(
                newToken,
                newRefreshToken,
                jwtTokenProvider.getExpirationTime(),
                user.isMustChangePassword(),
                new LoginResponse.UserDto(user)
        );
    }

    public LoginResponse.UserDto getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));

        return new LoginResponse.UserDto(user);
    }

    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new AuthenticationFailedException("Old password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Clear the forced password change flag after successful change
        user.setMustChangePassword(false);
        userRepository.save(user);

        auditLogService.log("CHANGE_PASSWORD", username, null, "User changed their password");
    }
}
