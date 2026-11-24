package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.McnServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface McnServerRepository extends JpaRepository<McnServer, Long> {
    List<McnServer> findByActiveTrue();
    List<McnServer> findByActiveTrueAndServerType(McnServer.ServerType serverType);
}
