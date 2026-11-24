package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.SshSessionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SshSessionLogRepository extends JpaRepository<SshSessionLog, Long> {
    Page<SshSessionLog> findByUsername(String username, Pageable pageable);
}
