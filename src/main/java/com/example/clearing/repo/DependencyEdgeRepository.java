package com.example.clearing.repo;

import com.example.clearing.domain.DependencyEdge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependencyEdgeRepository extends JpaRepository<DependencyEdge, Long> {
}
