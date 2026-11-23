package com.example.clearing.repo;

import com.example.clearing.domain.CapacityProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapacityProfileRepository extends JpaRepository<CapacityProfile, String> {
}
