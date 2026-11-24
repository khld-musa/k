package com.example.ussdGateway.api.dto;

import jakarta.validation.constraints.*;

import lombok.Data;
import java.util.List;

@Data
public class ContactGroupDto {
    private Long id;
    @NotBlank(message = "Group name is required")
    @Size(max = 255, message = "Group name must not exceed 255 characters")
    private String name;
    private String description;
    private List<ContactDto> contacts;
}
