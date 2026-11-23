package com.example.clearing.repo;

import com.example.clearing.domain.TaskEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {

    List<TaskEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
