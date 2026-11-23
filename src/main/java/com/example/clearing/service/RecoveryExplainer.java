package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.FailureRecord;
import com.example.clearing.domain.TaskEvent;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.FailureRecordRepository;
import com.example.clearing.repo.TaskEventRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 恢复解释器：说明一次任务为何等待、被限流、重投或升级人工。
 * 输出当前结论（summary）+ 完整决策时间线（timeline）+ 失败判定记录。
 */
@Service
public class RecoveryExplainer {

    private final ClearingTaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final FailureRecordRepository failureRepository;
    private final TokenBucketService bucketService;

    public RecoveryExplainer(ClearingTaskRepository taskRepository,
            TaskEventRepository eventRepository,
            FailureRecordRepository failureRepository,
            TokenBucketService bucketService) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.failureRepository = failureRepository;
        this.bucketService = bucketService;
    }

    public record Explanation(ClearingTask task, String summary, List<TaskEvent> timeline,
            List<FailureRecord> failures) {
    }

    public Explanation explain(Long taskId) {
        ClearingTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        List<TaskEvent> timeline = eventRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
        List<FailureRecord> failures = failureRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
        return new Explanation(task, summarize(task), timeline, failures);
    }

    /** 按当前状态与最近一次决策生成人话结论 */
    public String summarize(ClearingTask task) {
        TaskStatus status = task.getStatus();
        Decision last = task.getLastDecision();
        String dep = task.getDependencyCode();
        var bucket = bucketService.get(dep);
        String bucketInfo = bucket == null ? ""
                : "（当前桶余额 " + bucket.getTokens() + "/" + bucket.getCapacity()
                        + (bucket.isFrozen() ? "，已冻结" : "") + "）";
        if (status == TaskStatus.CONTENT_CONFLICT) {
            return "同业务键已存在不同内容的清单，判定内容冲突：未发放任何尝试令牌，等待人工裁定（保留原清单或以新内容覆盖）。";
        }
        if (status == TaskStatus.WAITING_DEPENDENCY) {
            return "任务在等待依赖恢复：" + nullSafe(task.getLastReason()) + "。等待期间不消耗令牌。";
        }
        if (status == TaskStatus.READY && last == Decision.THROTTLED) {
            return "任务就绪但被限流：依赖 " + dep + " 本地令牌桶余额不足" + bucketInfo
                    + "，等待协调器下一周期配额；队列按金额优先级与清算日排序。";
        }
        if (status == TaskStatus.READY && last == Decision.WAIT_DEPENDENCY_DOWN) {
            return "依赖 " + dep + " 不可用，本地令牌桶已冻结（不放行、不补充）" + bucketInfo
                    + "；恢复后按剩余预算渐进投放，不补发故障期间的全部尝试。";
        }
        if (status == TaskStatus.READY) {
            return "任务就绪，等待调度器投放（需同时满足：令牌充足、退避时间已到、依赖健康）。";
        }
        if (status == TaskStatus.RETRY_SCHEDULED) {
            return "发生技术瞬时故障，按指数退避等待重投：" + nullSafe(task.getLastReason());
        }
        if (status == TaskStatus.PENDING_CONFIRM) {
            return "基金回传已受理但确认回执延迟：幂等账本保持待确认，按操作号查询回执，"
                    + "不会取得新的扣款令牌，也不会重复入账。";
        }
        if (status == TaskStatus.MANUAL_REVIEW) {
            return "已升级人工裁定：" + nullSafe(task.getLastReason());
        }
        if (status == TaskStatus.CONFIRMED) {
            return "清算完成：扣款与基金回传均已入账并确认。";
        }
        if (status == TaskStatus.CANCELLED) {
            return "任务已被人工取消：" + nullSafe(task.getLastReason());
        }
        if (status == TaskStatus.FAILED_FINAL) {
            return "重试耗尽，任务终止：" + nullSafe(task.getLastReason());
        }
        return "当前状态 " + status + "：" + nullSafe(task.getLastReason());
    }

    private static String nullSafe(String s) {
        return s == null ? "（无）" : s;
    }
}
