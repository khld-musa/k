package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.request.LoginRequest;
import com.example.ussdGateway.entity.User;
import com.example.ussdGateway.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    // Mock StringRedisTemplate used by AuthService and TokenBlacklistService
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private com.example.ussdGateway.security.LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void setUp() {
        org.springframework.data.redis.core.ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        org.mockito.Mockito.when(loginRateLimiter.isAllowed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);

        userRepository.deleteAll();

        User user = new User();
        user.setUsername("testadmin");
        user.setEmail("testadmin@example.com");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(User.Role.ADMIN);
        user.setActive(true);
        userRepository.save(user);
    }

    @Test
    void testLoginSuccess() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("testadmin");
        request.setPassword("Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.user.username").value("testadmin"));
    }

    @Test
    void testLoginFailure_BadCredentials() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("testadmin");
        request.setPassword("WrongPassword!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void testLoginFailure_UserNotFound() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("unknownuser");
        request.setPassword("Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }
}
