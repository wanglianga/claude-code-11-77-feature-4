package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.ClearingTaskRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工裁定：处理规则冲突、内容冲突与重试耗尽的任务。
 */
@Service
public class ManualResolutionService {

    public enum Action {
        /** 人工处理后重新排队 */
        REQUEUE,
        /** 取消该任务 */
        CANCEL,
        /** 内容冲突：保留原清单，冲突登记作废 */
        KEEP_ORIGINAL,
        /** 内容冲突：以新内容覆盖原清单 */
        OVERRIDE
    }

    private final ClearingTaskRepository taskRepository;
    private final EventLog eventLog;

    public ManualResolutionService(ClearingTaskRepository taskRepository, EventLog eventLog) {
        this.taskRepository = taskRepository;
        this.eventLog = eventLog;
    }

    @Transactional
    public ClearingTask resolve(Long taskId, Action action, String note) {
        ClearingTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        String suffix = (note == null || note.isBlank()) ? "" : "；备注：" + note;
        switch (action) {
            case REQUEUE -> {
                requireStatus(task, TaskStatus.MANUAL_REVIEW, TaskStatus.RETRY_SCHEDULED,
                        TaskStatus.FAILED_FINAL, TaskStatus.WAITING_DEPENDENCY);
                task.setStatus(TaskStatus.READY);
                task.setAttemptCount(0);
                task.setNextAttemptAt(Instant.now());
                eventLog.record(task, Decision.MANUAL_RESOLVED, "人工裁定重新排队" + suffix);
            }
            case CANCEL -> {
                task.setStatus(TaskStatus.CANCELLED);
                eventLog.record(task, Decision.CANCELLED, "人工取消任务" + suffix);
            }
            case KEEP_ORIGINAL -> {
                requireStatus(task, TaskStatus.CONTENT_CONFLICT);
                task.setStatus(TaskStatus.CANCELLED);
                eventLog.record(task, Decision.CANCELLED,
                        "人工裁定保留原清单，本冲突登记作废（未消耗过令牌）" + suffix);
            }
            case OVERRIDE -> {
                requireStatus(task, TaskStatus.CONTENT_CONFLICT);
                List<ClearingTask> siblings = taskRepository
                        .findByBusinessKeyAndBillVersion(task.getBusinessKey(), task.getBillVersion());
                for (ClearingTask sibling : siblings) {
                    if (!sibling.getId().equals(task.getId()) && sibling.getStatus() != TaskStatus.CANCELLED) {
                        sibling.setStatus(TaskStatus.CANCELLED);
                        eventLog.record(sibling, Decision.CANCELLED,
                                "人工裁定以新内容版本为准，本任务被覆盖取消" + suffix);
                        taskRepository.save(sibling);
                    }
                }
                task.setStatus(TaskStatus.READY);
                task.setNextAttemptAt(Instant.now());
                eventLog.record(task, Decision.MANUAL_RESOLVED, "人工裁定以本内容为准，重新排队" + suffix);
            }
        }
        return taskRepository.save(task);
    }

    private static void requireStatus(ClearingTask task, TaskStatus... allowed) {
        for (TaskStatus s : allowed) {
            if (task.getStatus() == s) {
                return;
            }
        }
        throw new IllegalStateException("任务 " + task.getId() + " 当前状态 " + task.getStatus()
                + " 不允许该裁定动作");
    }
}
