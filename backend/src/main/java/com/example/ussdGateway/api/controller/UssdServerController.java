package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.request.UssdServerRequest;
import com.example.ussdGateway.api.dto.response.UssdServerResponse;
import com.example.ussdGateway.entity.UssdServer;
import com.example.ussdGateway.repository.UssdServerRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/servers")
@RequiredArgsConstructor
public class UssdServerController {

    private final UssdServerRepository ussdServerRepository;

    @GetMapping
    public ResponseEntity<List<UssdServerResponse>> getAllServers() {
        List<UssdServerResponse> responses = ussdServerRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/active")
    public ResponseEntity<List<UssdServerResponse>> getActiveServers() {
        List<UssdServerResponse> responses = ussdServerRepository.findByActiveTrue().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UssdServerResponse> createServer(@Valid @RequestBody UssdServerRequest request) {
        UssdServer server = new UssdServer();
        updateEntity(server, request);
        UssdServer saved = ussdServerRepository.save(server);
        return ResponseEntity.ok(mapToResponse(saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UssdServerResponse> updateServer(@PathVariable Long id, @Valid @RequestBody UssdServerRequest request) {
        return ussdServerRepository.findById(id)
                .map(server -> {
                    updateEntity(server, request);
                    UssdServer saved = ussdServerRepository.save(server);
                    return ResponseEntity.ok(mapToResponse(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteServer(@PathVariable Long id) {
        if (ussdServerRepository.existsById(id)) {
            ussdServerRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }


    private UssdServerResponse mapToResponse(UssdServer server) {
        UssdServerResponse response = new UssdServerResponse();
        response.setId(server.getId());
        response.setName(server.getName());
        response.setIpAddress(server.getIpAddress());
        response.setDescription(server.getDescription());
        response.setLogPath(server.getLogPath());
        response.setLocalLogPath(server.getLocalLogPath());
        response.setActive(server.isActive());
        response.setSshUsername(server.getSshUsername());
        response.setHasPasswordStored(server.getSshPassword() != null);
        response.setFtpUsername(server.getFtpUsername());
        response.setHasFtpPasswordStored(server.getFtpPassword() != null);
        response.setFtpRemotePath(server.getFtpRemotePath());
        response.setLastFetchDate(server.getLastFetchDate());
        response.setCreatedAt(server.getCreatedAt());
        response.setUpdatedAt(server.getUpdatedAt());
        return response;
    }

    private void updateEntity(UssdServer server, UssdServerRequest request) {
        server.setName(request.getName());
        server.setIpAddress(request.getIpAddress());
        server.setDescription(request.getDescription());
        server.setLogPath(request.getLogPath());
        if (request.getLocalLogPath() != null && !request.getLocalLogPath().isBlank()) {
            server.setLocalLogPath(request.getLocalLogPath());
        }
        server.setActive(request.isActive());
        server.setSshUsername(request.getSshUsername());
        if (request.getSshPassword() != null && !request.getSshPassword().isBlank()) {
            server.setSshPassword(request.getSshPassword());
        }
        server.setFtpUsername(request.getFtpUsername());
        if (request.getFtpPassword() != null && !request.getFtpPassword().isBlank()) {
            server.setFtpPassword(request.getFtpPassword());
        }
        if (request.getFtpRemotePath() != null && !request.getFtpRemotePath().isBlank()) {
            server.setFtpRemotePath(request.getFtpRemotePath());
        }
    }
}
