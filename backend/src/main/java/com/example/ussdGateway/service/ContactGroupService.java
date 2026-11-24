package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.ContactDto;
import com.example.ussdGateway.api.dto.ContactGroupDto;
import com.example.ussdGateway.entity.Contact;
import com.example.ussdGateway.entity.ContactGroup;
import com.example.ussdGateway.repository.ContactGroupRepository;
import com.example.ussdGateway.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContactGroupService {
    private final ContactGroupRepository groupRepository;
    private final ContactRepository contactRepository;
    private final AuditLogService auditLogService;

    public List<ContactGroupDto> getAllGroups() {
        return groupRepository.findAll().stream().map(this::mapToDto).collect(Collectors.toList());
    }

    public ContactGroupDto getGroupById(Long id) {
        return groupRepository.findById(id)
                .map(this::mapToDto)
                .orElseThrow(() -> new RuntimeException("Group not found"));
    }

    public ContactGroupDto createGroup(ContactGroupDto dto) {
        ContactGroup group = new ContactGroup();
        group.setName(dto.getName());
        group.setDescription(dto.getDescription());
        group = groupRepository.save(group);
        auditLogService.logActivity("CREATE_GROUP", "Created group: " + group.getName());
        return mapToDto(group);
    }

    public ContactGroupDto updateGroup(Long id, ContactGroupDto dto) {
        ContactGroup group = groupRepository.findById(id).orElseThrow(() -> new RuntimeException("Group not found"));
        String oldName = group.getName();
        group.setName(dto.getName());
        group.setDescription(dto.getDescription());
        group = groupRepository.save(group);
        auditLogService.logActivity("UPDATE_GROUP", "Updated group from '" + oldName + "' to '" + group.getName() + "'");
        return mapToDto(group);
    }

    public void deleteGroup(Long id) {
        ContactGroup group = groupRepository.findById(id).orElse(null);
        if (group != null) {
            String name = group.getName();
            groupRepository.deleteById(id);
            auditLogService.logActivity("DELETE_GROUP", "Deleted group: " + name);
        }
    }

    public ContactDto addContact(Long groupId, ContactDto dto) {
        ContactGroup group = groupRepository.findById(groupId).orElseThrow(() -> new RuntimeException("Group not found"));
        Contact contact = new Contact();
        contact.setName(dto.getName());
        contact.setEmail(dto.getEmail());
        contact.setPhoneNumber(dto.getPhoneNumber());
        contact.setGroup(group);
        contact = contactRepository.save(contact);
        auditLogService.logActivity("ADD_CONTACT", "Added contact '" + contact.getName() + "' to group '" + group.getName() + "'");
        dto.setId(contact.getId());
        return dto;
    }

    public void removeContact(Long contactId) {
        contactRepository.findById(contactId).ifPresent(contact -> {
            String name = contact.getName();
            String groupName = contact.getGroup() != null ? contact.getGroup().getName() : "Unknown";
            contactRepository.deleteById(contactId);
            auditLogService.logActivity("REMOVE_CONTACT", "Removed contact '" + name + "' from group '" + groupName + "'");
        });
    }

    private ContactGroupDto mapToDto(ContactGroup group) {
        ContactGroupDto dto = new ContactGroupDto();
        dto.setId(group.getId());
        dto.setName(group.getName());
        dto.setDescription(group.getDescription());
        
        List<ContactDto> contactDtos = contactRepository.findByGroupId(group.getId()).stream().map(c -> {
            ContactDto cDto = new ContactDto();
            cDto.setId(c.getId());
            cDto.setName(c.getName());
            cDto.setEmail(c.getEmail());
            cDto.setPhoneNumber(c.getPhoneNumber());
            return cDto;
        }).collect(Collectors.toList());
        
        dto.setContacts(contactDtos);
        return dto;
    }
}
