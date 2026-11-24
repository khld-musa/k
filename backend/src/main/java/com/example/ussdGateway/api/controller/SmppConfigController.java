package com.example.ussdGateway.api.controller;

import jakarta.validation.Valid;

import com.example.ussdGateway.api.dto.SmppConfigDto;
import com.example.ussdGateway.service.SmppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/smpp-configs")
@RequiredArgsConstructor
public class SmppConfigController {

    private final SmppConfigService service;

    @GetMapping
    public ResponseEntity<List<SmppConfigDto>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PostMapping
    public ResponseEntity<SmppConfigDto> save(@Valid @RequestBody SmppConfigDto dto) {
        return ResponseEntity.ok(service.save(dto));
    }

    @PostMapping("/test")
    public ResponseEntity<String> testConnection(@Valid @RequestBody SmppConfigDto dto) {
        try {
            service.testConnection(dto);
            return ResponseEntity.ok("SMPP Bind Successful");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("SMPP Bind failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }
}
