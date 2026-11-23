package com.example.clearing.repo;

import com.example.clearing.domain.QuotaGrant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotaGrantRepository extends JpaRepository<QuotaGrant, Long> {

    List<QuotaGrant> findByOrderByCreatedAtDesc(Pageable pageable);

    List<QuotaGrant> findByDependencyCodeOrderByCreatedAtDesc(String dependencyCode, Pageable pageable);
}
