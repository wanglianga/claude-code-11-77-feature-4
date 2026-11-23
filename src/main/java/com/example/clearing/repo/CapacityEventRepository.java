package com.example.clearing.repo;

import com.example.clearing.domain.CapacityEvent;
import com.example.clearing.domain.CapacityEventType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapacityEventRepository extends JpaRepository<CapacityEvent, Long> {

    List<CapacityEvent> findByDependencyCodeOrderByCreatedAtAsc(String dependencyCode);

    List<CapacityEvent> findByEventTypeOrderByCreatedAtAsc(CapacityEventType eventType);

    List<CapacityEvent> findByOrderByCreatedAtDesc(Pageable pageable);
}
