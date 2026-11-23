package com.example.clearing.repo;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.TaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClearingTaskRepository extends JpaRepository<ClearingTask, Long> {

    List<ClearingTask> findByBusinessKeyAndBillVersion(String businessKey, String billVersion);

    Optional<ClearingTask> findByBusinessKeyAndBillVersionAndContentHash(String businessKey, String billVersion,
            String contentHash);

    List<ClearingTask> findByStatusInAndDependencyCode(Collection<TaskStatus> statuses, String dependencyCode);

    List<ClearingTask> findByStatusIn(Collection<TaskStatus> statuses);

    List<ClearingTask> findByStatus(TaskStatus status);

    long countByStatusInAndDependencyCode(Collection<TaskStatus> statuses, String dependencyCode);

    List<ClearingTask> findByDependencyCode(String dependencyCode);
}
