package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.ContactGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContactGroupRepository extends JpaRepository<ContactGroup, Long> {
}
