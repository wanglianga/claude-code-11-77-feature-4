package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.CapacityEventType;
import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.domain.ProfileState;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 降级编排器：按"连续失败调度周期数"逐级收紧故障依赖自身的容量——
 * NORMAL → DEGRADED（生效上限减半）→ DOWN（生效上限置零、冻结本地桶）。
 * 局部限流：每次只收紧出现失败聚集的那一个依赖，其他依赖的画像、
 * 令牌桶与配额不受影响（数据库健康时基金接口故障绝不拖垮数据库队列）。
 * 人工覆盖（manualOverride）的依赖不由自动化编排接管。
 */
@Service
public class DegradationOrchestrator {

    private final CapacityProfileService profileService;
    private final ClearingProperties properties;

    /** 本周期各依赖的尝试/失败计数（调度器投放时上报，下一周期开始时消费） */
    private final Map<String, int[]> currentTickSignals = new ConcurrentHashMap<>();
    /** 上一周期的信号快照（供编排与恢复节流读取） */
    private final Map<String, int[]> lastTickSignals = new ConcurrentHashMap<>();

    public DegradationOrchestrator(CapacityProfileService profileService, ClearingProperties properties) {
        this.profileService = profileService;
        this.properties = properties;
    }

    /** 调度器投放后上报一次真实调用结果（瞬时故障才算依赖失败；规则冲突/报文错误不算） */
    public void reportOutcome(String dependencyCode, boolean success) {
        int[] counters = currentTickSignals.computeIfAbsent(dependencyCode, k -> new int[2]);
        synchronized (counters) {
            counters[0]++;
            if (!success) {
                counters[1]++;
            }
        }
        profileService.recordOutcome(dependencyCode, success);
    }

    public int attemptsLastTick(String dependencyCode) {
        int[] s = lastTickSignals.get(dependencyCode);
        return s == null ? 0 : s[0];
    }

    public int failuresLastTick(String dependencyCode) {
        int[] s = lastTickSignals.get(dependencyCode);
        return s == null ? 0 : s[1];
    }

    /**
     * 每个调度周期开始时的编排：消费上一周期信号，更新连续失败周期数并逐级降级。
     * 无尝试的周期保持原计数（既不升级也不洗白）；有尝试且零失败的周期清零。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void orchestrateTick() {
        lastTickSignals.clear();
        lastTickSignals.putAll(currentTickSignals);
        currentTickSignals.clear();

        ClearingProperties.Escalator cfg = properties.getCapacity().getEscalator();
        for (CapacityProfile profile : profileService.list()) {
            String code = profile.getDependencyCode();
            int attempts = attemptsLastTick(code);
            int failures = failuresLastTick(code);
            if (failures > 0) {
                profile.setConsecutiveFailureTicks(profile.getConsecutiveFailureTicks() + 1);
            } else if (attempts > 0) {
                profile.setConsecutiveFailureTicks(0);
            }
            if (profile.isManualOverride()) {
                profileService.save(profile);
                continue;
            }
            int streak = profile.getConsecutiveFailureTicks();
            switch (profile.getState()) {
                case NORMAL -> {
                    if (streak >= cfg.getFailureTicksToDegrade()) {
                        profileService.transition(profile, ProfileState.DEGRADED,
                                degradedCeiling(profile), CapacityEventType.ENTER_DEGRADED,
                                "连续 " + streak + " 个调度周期出现瞬时失败（上一周期尝试 " + attempts
                                        + " 次失败 " + failures + " 次），局部限流：本依赖生效上限降为 "
                                        + degradedCeiling(profile) + "/周期（声明上限 "
                                        + profile.getDeclaredCeiling() + "），其他依赖不受影响");
                    } else {
                        profileService.save(profile);
                    }
                }
                case DEGRADED -> {
                    if (streak >= cfg.getFailureTicksToDown()) {
                        profile.setConsecutiveProbeOk(0);
                        profileService.transition(profile, ProfileState.DOWN, 0,
                                CapacityEventType.ENTER_DOWN,
                                "失败持续 " + streak + " 个周期，局部熔断：本依赖生效上限置零、本地令牌桶冻结，"
                                        + "改由探测预算内的健康探针监视恢复；其他依赖队列照常推进");
                    } else {
                        profileService.save(profile);
                    }
                }
                default -> profileService.save(profile);
            }
        }
    }

    /** DEGRADED 生效上限：max(1, floor(声明上限 × 降级系数))，至少保留 1 个探测性流量 */
    public int degradedCeiling(CapacityProfile profile) {
        double factor = properties.getCapacity().getEscalator().getDegradedFactor();
        return Math.max(1, (int) Math.floor(profile.getDeclaredCeiling() * factor));
    }

    /** 演练复位：清空信号计数 */
    public void resetSignals() {
        currentTickSignals.clear();
        lastTickSignals.clear();
    }
}
