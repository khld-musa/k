package com.example.kraken_apn.repository;

import com.example.kraken_apn.entity.ApnRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApnRequestRepository extends JpaRepository<ApnRequest, Long> {
}
