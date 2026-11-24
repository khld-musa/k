package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.request.LoginRequest;
import com.example.ussdGateway.api.dto.response.LoginResponse;
import com.example.ussdGateway.exception.AuthenticationFailedException;
import com.example.ussdGateway.security.LoginRateLimiter;
import com.example.ussdGateway.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authentication Controller
 * Handles user login, logout, and token refresh
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                    HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();

        // Rate limit check
        if (!loginRateLimiter.isAllowed(clientIp, request.getUsername())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many login attempts. Please try again later."));
        }

        try {
            LoginResponse response = authService.login(request);
            loginRateLimiter.recordSuccess(clientIp, request.getUsername());
            return ResponseEntity.ok(response);
        } catch (AuthenticationFailedException e) {
            loginRateLimiter.recordFailure(clientIp, request.getUsername());
            throw e; // Let GlobalExceptionHandler handle it
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String token) {
        authService.logout(token);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestBody String refreshToken) {
        LoginResponse response = authService.refreshToken(refreshToken);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        return ResponseEntity.ok(authService.getCurrentUser());
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestParam String oldPassword,
            @RequestParam String newPassword) {
        // Validate new password complexity
        if (newPassword == null || newPassword.length() < 8 ||
                !newPassword.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$")) {
            throw new IllegalArgumentException(
                    "New password must be at least 8 characters with uppercase, lowercase, digit, and special character (@$!%*?&)");
        }
        authService.changePassword(oldPassword, newPassword);
        return ResponseEntity.ok().build();
    }
}
