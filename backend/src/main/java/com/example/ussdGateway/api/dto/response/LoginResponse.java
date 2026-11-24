package com.example.ussdGateway.api.dto.response;

import com.example.ussdGateway.entity.User;

public class LoginResponse {

    private String token;
    private String refreshToken;
    private Long expiresIn;
    private boolean mustChangePassword;
    private UserDto user;

    public LoginResponse() {}

    public LoginResponse(String token, String refreshToken, Long expiresIn, boolean mustChangePassword, UserDto user) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
        this.mustChangePassword = mustChangePassword;
        this.user = user;
    }

    // Getters and Setters
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public Long getExpiresIn() { return expiresIn; }
    public void setExpiresIn(Long expiresIn) { this.expiresIn = expiresIn; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public UserDto getUser() { return user; }
    public void setUser(UserDto user) { this.user = user; }

    public static class UserDto {
        private Long id;
        private String username;
        private String email;
        private String role;
        private boolean active;
        private boolean mustChangePassword;

        public UserDto() {}

        public UserDto(User user) {
            this.id = user.getId();
            this.username = user.getUsername();
            this.email = user.getEmail();
            this.role = user.getRole().name();
            this.active = user.isActive();
            this.mustChangePassword = user.isMustChangePassword();
        }

        // Getters and Setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        public boolean isMustChangePassword() { return mustChangePassword; }
        public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    }
}
