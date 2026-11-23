package com.example.clearing.repo;

import com.example.clearing.domain.StormReport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StormReportRepository extends JpaRepository<StormReport, Long> {

    Optional<StormReport> findTopByDependencyCodeOrderByCreatedAtDesc(String dependencyCode);

    List<StormReport> findByOrderByCreatedAtDesc(Pageable pageable);
}
