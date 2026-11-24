package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.request.McnServerRequest;
import com.example.ussdGateway.api.dto.response.McnServerResponse;
import com.example.ussdGateway.entity.McnServer;
import com.example.ussdGateway.repository.McnServerRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/mcn-servers")
@RequiredArgsConstructor
public class McnServerController {

    private final McnServerRepository mcnServerRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<McnServerResponse>> getAllServers() {
        List<McnServerResponse> responses = mcnServerRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<McnServerResponse>> getActiveServers() {
        List<McnServerResponse> responses = mcnServerRepository.findByActiveTrue().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<McnServerResponse> createServer(@Valid @RequestBody McnServerRequest request) {
        McnServer server = new McnServer();
        updateEntity(server, request);
        McnServer saved = mcnServerRepository.save(server);
        return ResponseEntity.ok(mapToResponse(saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<McnServerResponse> updateServer(@PathVariable Long id, @Valid @RequestBody McnServerRequest request) {
        return mcnServerRepository.findById(id)
                .map(server -> {
                    updateEntity(server, request);
                    McnServer saved = mcnServerRepository.save(server);
                    return ResponseEntity.ok(mapToResponse(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteServer(@PathVariable Long id) {
        if (mcnServerRepository.existsById(id)) {
            mcnServerRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private McnServerResponse mapToResponse(McnServer server) {
        McnServerResponse response = new McnServerResponse();
        response.setId(server.getId());
        response.setName(server.getName());
        response.setIpAddress(server.getIpAddress());
        response.setDescription(server.getDescription());
        response.setLogPath(server.getLogPath());
        response.setServerType(server.getServerType());
        response.setActive(server.isActive());
        response.setSshUsername(server.getSshUsername());
        response.setHasPasswordStored(server.getSshPassword() != null);
        response.setCreatedAt(server.getCreatedAt());
        response.setUpdatedAt(server.getUpdatedAt());
        return response;
    }

    private void updateEntity(McnServer server, McnServerRequest request) {
        server.setName(request.getName());
        server.setIpAddress(request.getIpAddress());
        server.setDescription(request.getDescription());
        server.setLogPath(request.getLogPath());
        server.setServerType(request.getServerType());
        server.setActive(request.isActive());
        server.setSshUsername(request.getSshUsername());
        if (request.getSshPassword() != null && !request.getSshPassword().isBlank()) {
            server.setSshPassword(request.getSshPassword());
        }
    }
}
