package com.example.ussdGateway.security;

import com.example.ussdGateway.service.DownloadTicketService;
import com.example.ussdGateway.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private TokenBlacklistService tokenBlacklistService;
    private DownloadTicketService downloadTicketService;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public void setTokenBlacklistService(TokenBlacklistService tokenBlacklistService) {
        this.tokenBlacklistService = tokenBlacklistService;
    }

    public void setDownloadTicketService(DownloadTicketService downloadTicketService) {
        this.downloadTicketService = downloadTicketService;
    }

    /** Authenticates a browser-native export download using a short-lived, job-bound ticket. */
    private boolean authenticateWithDownloadTicket(HttpServletRequest request) {
        String ticket = request.getParameter("ticket");
        if (downloadTicketService == null || !StringUtils.hasText(ticket)) {
            return false;
        }
        java.util.regex.Matcher m = DOWNLOAD_PATH.matcher(request.getRequestURI());
        if (!m.matches()) {
            return false;
        }
        return downloadTicketService.resolve(ticket, m.group(1)).map(t -> {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    t.username(), null, Collections.singletonList(new SimpleGrantedAuthority(t.authority())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            return true;
        }).orElse(false);
    }

    private static final java.util.regex.Pattern DOWNLOAD_PATH = java.util.regex.Pattern.compile(
            ".*/reports/jobs/([0-9a-fA-F-]{36})/download$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        try {
            if (authenticateWithDownloadTicket(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && jwtTokenProvider.validateToken(jwt)) {
                // Check if the token has been blacklisted (e.g., after logout)
                String jti = jwtTokenProvider.getJtiFromToken(jwt);
                if (jti != null && tokenBlacklistService != null && tokenBlacklistService.isBlacklisted(jti)) {
                    logger.debug("Rejected blacklisted token jti=" + jti);
                    filterChain.doFilter(request, response);
                    return;
                }

                String username = jwtTokenProvider.getUsernameFromToken(jwt);
                String role = jwtTokenProvider.getRoleFromToken(jwt);

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
                );

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // Fallback: allow token via query parameter for export endpoints
        // so the browser can download directly via window.location.href
        if (request.getRequestURI().contains("/export")) {
            String qToken = request.getParameter("token");
            if (StringUtils.hasText(qToken)) {
                return qToken;
            }
        }
        return null;
    }
}
