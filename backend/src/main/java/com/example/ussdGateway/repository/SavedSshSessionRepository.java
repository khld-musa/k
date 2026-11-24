package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.SavedSshSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SavedSshSessionRepository extends JpaRepository<SavedSshSession, Long> {
    List<SavedSshSession> findByUserId(Long userId);
    List<SavedSshSession> findByUserUsername(String username);
    boolean existsByUserIdAndName(Long userId, String name);
}
