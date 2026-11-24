package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.SmppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SmppConfigRepository extends JpaRepository<SmppConfig, Long> {
}
