package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.UssdServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UssdServerRepository extends JpaRepository<UssdServer, Long> {
    List<UssdServer> findByActiveTrue();
}
