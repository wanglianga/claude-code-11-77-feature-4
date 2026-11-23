package com.example.clearing.repo;

import com.example.clearing.domain.FailureRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailureRecordRepository extends JpaRepository<FailureRecord, Long> {

    long countByDependencyCodeAndCreatedAtAfter(String dependencyCode, Instant after);

    List<FailureRecord> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
