package com.example.ussdGateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;

import lombok.Data;

@Data
public class ContactDto {
    private Long id;
    @NotBlank(message = "Full name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    private String name;
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;
    @Pattern(regexp = "^$|^\\+?\\d{7,15}$", message = "Phone number must be 7-15 digits (optional + prefix)")
    private String phoneNumber;

    // Reported under the "contactMethod" key; a contact with neither channel can never be notified
    @JsonIgnore
    @AssertTrue(message = "Provide an email or a phone number")
    public boolean isContactMethod() {
        return (email != null && !email.isBlank()) || (phoneNumber != null && !phoneNumber.isBlank());
    }
}
