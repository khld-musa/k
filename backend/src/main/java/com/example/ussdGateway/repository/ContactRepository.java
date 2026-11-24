package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import java.util.Collection;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {
    List<Contact> findByGroupId(Long groupId);
    List<Contact> findByGroupIdIn(Collection<Long> groupIds);
}
