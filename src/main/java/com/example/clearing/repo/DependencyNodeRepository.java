package com.example.clearing.repo;

import com.example.clearing.domain.DependencyNode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependencyNodeRepository extends JpaRepository<DependencyNode, String> {
}
