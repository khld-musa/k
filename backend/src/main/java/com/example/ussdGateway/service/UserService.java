package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.request.UserCreateRequest;
import com.example.ussdGateway.api.dto.request.UserUpdateRequest;
import com.example.ussdGateway.api.dto.response.UserResponse;
import com.example.ussdGateway.entity.User;
import com.example.ussdGateway.exception.BusinessException;
import com.example.ussdGateway.exception.ResourceNotFoundException;
import com.example.ussdGateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(UserResponse::new)
                .collect(Collectors.toList());
    }

    public org.springframework.data.domain.Page<UserResponse> getAllUsers(org.springframework.data.domain.Pageable pageable) {
        return userRepository.findAll(pageable)
                .map(UserResponse::new);
    }

    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        return new UserResponse(user);
    }

    @Transactional
    public UserResponse createUser(UserCreateRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException("Username already exists: " + request.getUsername());
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already exists: " + request.getEmail());
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setActive(true);

        UserResponse response = new UserResponse(userRepository.save(user));
        auditLogService.log("CREATE_USER", getCurrentUsername(), request.getUsername(),
                "Created user with role " + request.getRole());
        return response;
    }

    @Transactional
    public UserResponse updateUser(Long id, UserUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new BusinessException("Email already exists: " + request.getEmail());
            }
            user.setEmail(request.getEmail());
        }

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            user.setMustChangePassword(false);
        }

        if (request.getRole() != null) {
            user.setRole(request.getRole());
        }

        if (request.getActive() != null) {
            user.setActive(request.getActive());
        }

        UserResponse response = new UserResponse(userRepository.save(user));
        auditLogService.log("UPDATE_USER", getCurrentUsername(), user.getUsername(),
                "Updated user details");
        return response;
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        // Prevent self-deletion
        String currentUsername = getCurrentUsername();
        if (user.getUsername().equals(currentUsername)) {
            throw new BusinessException("Cannot delete your own account");
        }

        String targetUsername = user.getUsername();
        userRepository.deleteById(id);
        auditLogService.log("DELETE_USER", currentUsername, targetUsername,
                "Deleted user");
    }

    @Transactional
    public UserResponse toggleUserStatus(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        // Prevent self-deactivation
        String currentUsername = getCurrentUsername();
        if (user.getUsername().equals(currentUsername)) {
            throw new BusinessException("Cannot change your own account status");
        }

        user.setActive(!user.isActive());
        UserResponse response = new UserResponse(userRepository.save(user));
        auditLogService.log("TOGGLE_USER_STATUS", currentUsername, user.getUsername(),
                "User status changed to " + (user.isActive() ? "active" : "inactive"));
        return response;
    }

    private String getCurrentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null ? auth.getName() : "system";
        } catch (Exception e) {
            return "system";
        }
    }
}
