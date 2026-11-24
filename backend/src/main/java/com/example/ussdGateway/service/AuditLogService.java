package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.AuditLog;
import com.example.ussdGateway.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Record an audit log entry.
     * Automatically captures the client IP address from the current HTTP request.
     */
    @Transactional
    public void log(String action, String username, String targetUser, String details, String ipAddress) {
        AuditLog entry = new AuditLog();
        entry.setAction(action);
        entry.setUsername(username);
        entry.setTargetUser(targetUser);
        entry.setDetails(details);
        entry.setIpAddress(ipAddress);
        auditLogRepository.save(entry);
        log.info("AUDIT: {} by {} on {} — {} [IP: {}]", action, username, targetUser, details, ipAddress);
    }

    /**
     * Records a system activity log, automatically resolving current user and IP.
     */
    public void logActivity(String action, String details) {
        log(action, getCurrentUsername(), "SYSTEM", details);
    }

    /**
     * Resolves the currently authenticated username from Spring Security context.
     */
    public String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "SYSTEM";
    }

    /**
     * Convenience overload that auto-resolves the client IP from the current request.
     */
    @Transactional
    public void log(String action, String username, String targetUser, String details) {
        log(action, username, targetUser, details, resolveClientIp());
    }

    /**
     * Return all audit logs ordered by most recent first.
     */
    public List<AuditLog> getAllLogs() {
        return auditLogRepository.findAllByOrderByTimestampDesc();
    }

    /**
     * Return paginated audit logs ordered by most recent first.
     */
    public org.springframework.data.domain.Page<AuditLog> getAllLogs(org.springframework.data.domain.Pageable pageable) {
        return auditLogRepository.findAllByOrderByTimestampDesc(pageable);
    }

    /**
     * Extracts the client IP from the current HTTP request context.
     * Handles proxied requests by checking X-Forwarded-For header.
     */
    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;

            HttpServletRequest request = attrs.getRequest();

            // Check for proxy headers first
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // X-Forwarded-For can contain multiple IPs; the first one is the client
                return ip.split(",")[0].trim();
            }

            ip = request.getHeader("X-Real-IP");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip;
            }

            return request.getRemoteAddr();
        } catch (Exception e) {
            log.warn("Could not resolve client IP: {}", e.getMessage());
            return null;
        }
    }
}
