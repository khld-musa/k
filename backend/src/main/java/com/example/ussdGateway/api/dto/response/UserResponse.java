package com.example.ussdGateway.api.dto.response;

import com.example.ussdGateway.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserResponse {
    private Long id;
    private String username;
    private String email;
    private String role;
    private boolean active;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;

    public UserResponse(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.email = user.getEmail();
        this.role = user.getRole().name();
        this.active = user.isActive();
        this.lastLogin = user.getLastLogin();
        this.createdAt = user.getCreatedAt();
    }
}
