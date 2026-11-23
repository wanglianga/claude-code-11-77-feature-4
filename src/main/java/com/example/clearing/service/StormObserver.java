package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.StormLevel;
import com.example.clearing.domain.StormReport;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.domain.TokenBucket;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.FailureRecordRepository;
import com.example.clearing.repo.StormReportRepository;
import com.example.clearing.repo.TokenBucketRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 风暴观测器：按依赖检测队列增长、令牌消耗与失败聚集，
 * 输出 NORMAL / WATCH / STORM 三级观测报告。
 */
@Service
public class StormObserver {

    private final StormReportRepository stormRepository;
    private final ClearingTaskRepository taskRepository;
    private final FailureRecordRepository failureRepository;
    private final TokenBucketRepository bucketRepository;
    private final ClearingProperties properties;

    /** 上一次观测快照（内存即可，重启后重新基线） */
    private final Map<String, Long> lastConsumed = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastDepth = new ConcurrentHashMap<>();

    public StormObserver(StormReportRepository stormRepository,
            ClearingTaskRepository taskRepository,
            FailureRecordRepository failureRepository,
            TokenBucketRepository bucketRepository,
            ClearingProperties properties) {
        this.stormRepository = stormRepository;
        this.taskRepository = taskRepository;
        this.failureRepository = failureRepository;
        this.bucketRepository = bucketRepository;
        this.properties = properties;
    }

    @Transactional
    public List<StormReport> observe() {
        List<StormReport> reports = new ArrayList<>();
        Instant windowStart = Instant.now()
                .minus(properties.getStorm().getWindowSeconds(), ChronoUnit.SECONDS);
        for (TokenBucket bucket : bucketRepository.findAll()) {
            String code = bucket.getDependencyCode();
            int depth = (int) taskRepository.countByStatusInAndDependencyCode(
                    List.of(TaskStatus.READY, TaskStatus.RETRY_SCHEDULED, TaskStatus.WAITING_DEPENDENCY), code);
            long failures = failureRepository.countByDependencyCodeAndCreatedAtAfter(code, windowStart);
            long consumedDelta = bucket.getTotalConsumed()
                    - lastConsumed.getOrDefault(code, bucket.getTotalConsumed());
            int growth = depth - lastDepth.getOrDefault(code, depth);
            lastConsumed.put(code, bucket.getTotalConsumed());
            lastDepth.put(code, depth);

            StormLevel level = classify(failures, growth);
            StormReport report = new StormReport();
            report.setDependencyCode(code);
            report.setStormLevel(level);
            report.setQueueDepth(depth);
            report.setQueueGrowth(growth);
            report.setTokensConsumedWindow(consumedDelta);
            report.setFailuresWindow(failures);
            report.setSummary(summarize(code, level, depth, growth, consumedDelta, failures));
            stormRepository.save(report);
            reports.add(report);
        }
        return reports;
    }

    private StormLevel classify(long failures, int growth) {
        ClearingProperties.Storm cfg = properties.getStorm();
        if (failures >= cfg.getFailureStormThreshold()) {
            return StormLevel.STORM;
        }
        if (failures >= cfg.getFailureWatchThreshold() || growth >= cfg.getQueueGrowthWatchThreshold()) {
            return StormLevel.WATCH;
        }
        return StormLevel.NORMAL;
    }

    private String summarize(String code, StormLevel level, int depth, int growth, long consumed, long failures) {
        ClearingProperties.Storm cfg = properties.getStorm();
        return switch (level) {
            case STORM -> "依赖 " + code + " 失败聚集：观测窗口内失败 " + failures + " 次（阈值 "
                    + cfg.getFailureStormThreshold() + "），队列深度 " + depth + "，判定 STORM，建议降级该依赖并人工介入";
            case WATCH -> "依赖 " + code + " 出现异常征兆：窗口失败 " + failures + " 次，队列增长 " + growth
                    + "，令牌消耗 " + consumed + "，判定 WATCH";
            case NORMAL -> "依赖 " + code + " 运行平稳：队列深度 " + depth + "，窗口失败 " + failures
                    + " 次，令牌消耗 " + consumed;
        };
    }

    public List<StormReport> latest() {
        List<StormReport> result = new ArrayList<>();
        for (TokenBucket bucket : bucketRepository.findAll()) {
            stormRepository.findTopByDependencyCodeOrderByCreatedAtDesc(bucket.getDependencyCode())
                    .ifPresent(result::add);
        }
        return result;
    }

    public List<StormReport> recent(int limit) {
        return stormRepository.findByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }

    public void resetWindow() {
        lastConsumed.clear();
        lastDepth.clear();
    }
}
