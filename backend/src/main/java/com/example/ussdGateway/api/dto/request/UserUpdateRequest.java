package com.example.ussdGateway.api.dto.request;

import com.example.ussdGateway.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserUpdateRequest {

    @Email(message = "Invalid email format")
    private String email;

    @Size(min = 6, max = 100, message = "Password must be at least 6 characters")
    private String password;

    private User.Role role;

    private Boolean active;
}
