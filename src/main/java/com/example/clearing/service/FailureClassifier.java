package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.FailureRecord;
import com.example.clearing.domain.FailureType;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.FailureRecordRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 失败判定器：把失败拆为可复核状态——
 * 技术瞬时故障（退避重试）、目录等待、规则冲突、内容不一致、人工裁定。
 */
@Service
public class FailureClassifier {

    private final FailureRecordRepository failureRepository;
    private final ClearingTaskRepository taskRepository;
    private final ClearingProperties properties;
    private final EventLog eventLog;

    public FailureClassifier(FailureRecordRepository failureRepository,
            ClearingTaskRepository taskRepository,
            ClearingProperties properties,
            EventLog eventLog) {
        this.failureRepository = failureRepository;
        this.taskRepository = taskRepository;
        this.properties = properties;
        this.eventLog = eventLog;
    }

    /** 技术瞬时故障：指数退避重试；重试耗尽升级人工裁定 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onTransient(ClearingTask task, String detail) {
        failureRepository.save(new FailureRecord(task.getId(), task.getDependencyCode(),
                FailureType.TECH_TRANSIENT, detail));
        int maxAttempts = properties.getBackoff().getMaxAttempts();
        if (task.getAttemptCount() >= maxAttempts) {
            failureRepository.save(new FailureRecord(task.getId(), task.getDependencyCode(),
                    FailureType.MANUAL_ADJUDICATION, "重试 " + task.getAttemptCount() + " 次仍失败，升级人工裁定"));
            task.setStatus(TaskStatus.MANUAL_REVIEW);
            eventLog.record(task, Decision.ESCALATED_MANUAL,
                    "瞬时故障重试 " + task.getAttemptCount() + " 次未果（上限 " + maxAttempts + "），升级人工裁定");
        } else {
            long backoffMs = backoffMs(task.getAttemptCount());
            task.setStatus(TaskStatus.RETRY_SCHEDULED);
            task.setNextAttemptAt(Instant.now().plus(backoffMs, ChronoUnit.MILLIS));
            eventLog.record(task, Decision.RETRY_SCHEDULED,
                    "技术瞬时故障：" + detail + "；第 " + task.getAttemptCount() + " 次尝试，退避 "
                            + backoffMs + "ms 后重投");
        }
        taskRepository.save(task);
    }

    /** 目录等待：目录项缺失或目录服务不可用，不消耗重试次数，等待目录恢复 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onCatalogWait(ClearingTask task, String detail) {
        failureRepository.save(new FailureRecord(task.getId(), "CATALOG",
                FailureType.CATALOG_WAIT, detail));
        task.setStatus(TaskStatus.WAITING_DEPENDENCY);
        eventLog.record(task, Decision.WAIT_CATALOG, detail);
        taskRepository.save(task);
    }

    /** 规则冲突：升级人工裁定 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onRuleConflict(ClearingTask task, String detail) {
        failureRepository.save(new FailureRecord(task.getId(), task.getDependencyCode(),
                FailureType.RULE_CONFLICT, detail));
        task.setStatus(TaskStatus.MANUAL_REVIEW);
        eventLog.record(task, Decision.ESCALATED_MANUAL, "待遇规则冲突：" + detail);
        taskRepository.save(task);
    }

    /** 内容不一致：同业务键不同内容，不发放令牌 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onContentMismatch(ClearingTask task, String detail) {
        failureRepository.save(new FailureRecord(task.getId(), task.getDependencyCode(),
                FailureType.CONTENT_MISMATCH, detail));
        task.setStatus(TaskStatus.CONTENT_CONFLICT);
        eventLog.record(task, Decision.CONTENT_CONFLICT, detail);
        taskRepository.save(task);
    }

    public long backoffMs(int attemptCount) {
        long base = properties.getBackoff().getBaseMs();
        long max = properties.getBackoff().getMaxMs();
        long backoff = base;
        for (int i = 1; i < attemptCount; i++) {
            backoff = Math.min(max, backoff * 2);
        }
        return Math.min(max, backoff);
    }
}
