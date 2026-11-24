package com.example.ussdGateway.api.controller;

import jakarta.validation.Valid;

import com.example.ussdGateway.api.dto.ContactDto;
import com.example.ussdGateway.api.dto.ContactGroupDto;
import com.example.ussdGateway.service.ContactGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class ContactGroupController {

    private final ContactGroupService groupService;

    @GetMapping
    public ResponseEntity<List<ContactGroupDto>> getAllGroups() {
        return ResponseEntity.ok(groupService.getAllGroups());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContactGroupDto> getGroupById(@PathVariable Long id) {
        return ResponseEntity.ok(groupService.getGroupById(id));
    }

    @PostMapping
    public ResponseEntity<ContactGroupDto> createGroup(@Valid @RequestBody ContactGroupDto dto) {
        return ResponseEntity.ok(groupService.createGroup(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ContactGroupDto> updateGroup(@PathVariable Long id, @Valid @RequestBody ContactGroupDto dto) {
        return ResponseEntity.ok(groupService.updateGroup(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        groupService.deleteGroup(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{groupId}/contacts")
    public ResponseEntity<ContactDto> addContactToGroup(@PathVariable Long groupId, @Valid @RequestBody ContactDto dto) {
        return ResponseEntity.ok(groupService.addContact(groupId, dto));
    }

    @DeleteMapping("/contacts/{contactId}")
    public ResponseEntity<Void> removeContactFromGroup(@PathVariable Long contactId) {
        groupService.removeContact(contactId);
        return ResponseEntity.ok().build();
    }
}
