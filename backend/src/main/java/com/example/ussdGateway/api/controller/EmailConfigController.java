package com.example.ussdGateway.api.controller;

import jakarta.validation.Valid;

import com.example.ussdGateway.api.dto.EmailConfigDto;
import com.example.ussdGateway.service.EmailConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/email-configs")
@RequiredArgsConstructor
public class EmailConfigController {

    private final EmailConfigService service;

    @GetMapping
    public ResponseEntity<List<EmailConfigDto>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PostMapping
    public ResponseEntity<EmailConfigDto> save(@Valid @RequestBody EmailConfigDto dto) {
        return ResponseEntity.ok(service.save(dto));
    }

    @PostMapping("/test")
    public ResponseEntity<String> testConnection(@Valid @RequestBody EmailConfigDto dto) {
        try {
            service.testConnection(dto);
            return ResponseEntity.ok("Connection Successful");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Connection failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }
}
