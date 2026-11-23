package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.CapacityEventType;
import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.domain.ProfileState;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 依赖健康探针：对处于 DOWN / RECOVERING 的依赖做主动探测。
 * 探测本身是被保护对象——严格受探测预算约束：
 *  - 每个依赖每个调度周期最多 maxPerTickPerDep 次；
 *  - 全部依赖每个调度周期合计最多 maxPerTickTotal 次；
 * 超出预算的探测被跳过并记录 PROBE_SKIPPED_BUDGET（探测上限可审计）。
 * 探测不消耗业务令牌，也不把任何依赖用量推过声明上限。
 */
@Service
public class DependencyHealthProbe {

    /** 清算数据库依赖编码（容量画像与本地桶保护账本写入） */
    public static final String DEP_DB = "CLEARING_DB";

    private final CapacityProfileService profileService;
    private final DownstreamSimulator simulator;
    private final ClearingProperties properties;
    private final EntityManager entityManager;

    /** 本调度周期全局已执行探测数（内存记账，每周期重置） */
    private int probesThisTickTotal = 0;

    public DependencyHealthProbe(CapacityProfileService profileService,
            DownstreamSimulator simulator,
            ClearingProperties properties,
            EntityManager entityManager) {
        this.profileService = profileService;
        this.simulator = simulator;
        this.properties = properties;
        this.entityManager = entityManager;
    }

    /**
     * 每个调度周期执行一轮探测。返回本轮实际执行的探测次数。
     * 人工覆盖（manualOverride）的依赖不探测：操作员权威优先。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public synchronized int probeTick() {
        probesThisTickTotal = 0;
        List<CapacityProfile> profiles = profileService.list();
        // 先重置每周期探测计数
        for (CapacityProfile p : profiles) {
            if (p.getProbesThisTick() != 0) {
                p.setProbesThisTick(0);
                profileService.save(p);
            }
        }
        int executed = 0;
        for (CapacityProfile p : profiles) {
            if (p.isManualOverride()) {
                continue;
            }
            if (p.getState() != ProfileState.DOWN && p.getState() != ProfileState.RECOVERING) {
                continue;
            }
            if (!withinBudget(p)) {
                continue;
            }
            boolean ok = probe(p.getDependencyCode());
            executed++;
            applyResult(p, ok);
        }
        return executed;
    }

    /** 探测预算检查；超预算记录跳过事件（探测上限的审计证据） */
    private boolean withinBudget(CapacityProfile p) {
        ClearingProperties.Probe cfg = properties.getCapacity().getProbe();
        if (p.getProbesThisTick() >= cfg.getMaxPerTickPerDep()
                || probesThisTickTotal >= cfg.getMaxPerTickTotal()) {
            profileService.recordEvent(p.getDependencyCode(), CapacityEventType.PROBE_SKIPPED_BUDGET,
                    p.getEffectiveCeiling(),
                    "探测预算上限保护：本周期该依赖已探测 " + p.getProbesThisTick() + " 次（上限 "
                            + cfg.getMaxPerTickPerDep() + "），全局已探测 " + probesThisTickTotal
                            + " 次（上限 " + cfg.getMaxPerTickTotal() + "），本次探测跳过，避免探测本身压垮依赖");
            return false;
        }
        return true;
    }

    /** 执行一次探测：清算库真实 SELECT 1；规则/基金走模拟器探针 */
    private boolean probe(String dependencyCode) {
        try {
            return switch (dependencyCode) {
                case DEP_DB -> entityManager.createNativeQuery("SELECT 1").getSingleResult() != null;
                case TaskRegistrar.DEP_RULE -> simulator.pingRule();
                case TaskRegistrar.DEP_FUND -> simulator.pingFund();
                default -> true;
            };
        } catch (Exception e) {
            return false;
        }
    }

    private void applyResult(CapacityProfile p, boolean ok) {
        p.setProbesThisTick(p.getProbesThisTick() + 1);
        p.setTotalProbes(p.getTotalProbes() + 1);
        probesThisTickTotal++;
        if (ok) {
            p.setConsecutiveProbeOk(p.getConsecutiveProbeOk() + 1);
            profileService.save(p);
            profileService.recordEvent(p.getDependencyCode(), CapacityEventType.PROBE_OK,
                    p.getEffectiveCeiling(),
                    "探测成功（连续 " + p.getConsecutiveProbeOk() + " 次，达到 "
                            + properties.getCapacity().getProbe().getConsecutiveOkToRecover()
                            + " 次才允许恢复爬坡）");
        } else {
            p.setConsecutiveProbeOk(0);
            p.setTotalProbeFailures(p.getTotalProbeFailures() + 1);
            profileService.save(p);
            profileService.recordEvent(p.getDependencyCode(), CapacityEventType.PROBE_FAIL,
                    p.getEffectiveCeiling(), "探测失败：连续成功计数清零（抗抖动：短暂恢复信号不被追认）");
        }
    }
}
