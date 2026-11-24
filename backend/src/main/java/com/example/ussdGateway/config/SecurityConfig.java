package com.example.ussdGateway.config;

import com.example.ussdGateway.security.JwtAuthFilter;
import com.example.ussdGateway.security.JwtTokenProvider;
import com.example.ussdGateway.service.DownloadTicketService;
import com.example.ussdGateway.service.TokenBlacklistService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security Configuration
 * Configures JWT-based authentication and authorization
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    private final DownloadTicketService downloadTicketService;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider, TokenBlacklistService tokenBlacklistService,
                          DownloadTicketService downloadTicketService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
        this.downloadTicketService = downloadTicketService;
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        JwtAuthFilter filter = new JwtAuthFilter(jwtTokenProvider);
        filter.setTokenBlacklistService(tokenBlacklistService);
        filter.setDownloadTicketService(downloadTicketService);
        return filter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.OPTIONS, "/**")).permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/api/v1/auth/**")).permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/api/v1/ussd/**")).permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/api/v1/logs/live-trace/stream")).permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/api/v1/system-settings/**")).hasRole("ADMIN")
                // Actuator: only health is public (for Docker/LB health checks), rest requires ADMIN
                .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/health")).permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/**")).hasRole("ADMIN")
                // Swagger: require authentication (any authenticated user can view API docs)
                .requestMatchers(AntPathRequestMatcher.antMatcher("/swagger-ui/**"), AntPathRequestMatcher.antMatcher("/v3/api-docs/**")).authenticated()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
