package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.AlarmConfig;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlarmConfigRepository extends JpaRepository<AlarmConfig, Long> {

    /**
     * Returns only active configs — used by the scheduler to avoid loading/skipping inactive ones.
     * The EntityGraph eagerly fetches subscribedGroups in a single JOIN query, preventing N+1.
     */
    @EntityGraph(attributePaths = {"subscribedGroups"})
    List<AlarmConfig> findByIsActiveTrue();
}
