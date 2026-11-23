package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.clearing.domain.CapacityEventType;
import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.domain.ProfileState;
import com.example.clearing.service.CapacityProfileService;
import com.example.clearing.service.DemoScenarioService;
import com.example.clearing.service.DependencyHealthProbe;
import com.example.clearing.service.DownstreamSimulator;
import com.example.clearing.service.RecoveryThrottler;
import com.example.clearing.service.TaskRegistrar;
import com.example.clearing.service.TokenBucketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 恢复节流器：短暂恢复信号不被追认；确认后从下限保守爬坡，绝不越过声明上限 */
@SpringBootTest
class RecoveryThrottleFlowTest {

    @Autowired
    private DependencyHealthProbe probe;

    @Autowired
    private RecoveryThrottler throttler;

    @Autowired
    private CapacityProfileService profileService;

    @Autowired
    private TokenBucketService bucketService;

    @Autowired
    private DownstreamSimulator simulator;

    @Autowired
    private DemoScenarioService demo;

    @BeforeEach
    void reset() {
        demo.reset();
    }

    @Test
    void briefRecoveryBlipIsNotTrusted_thenConservativeRampUp() {
        String dep = TaskRegistrar.DEP_RULE;
        // 接口故障 → 画像熔断 DOWN（生效上限 0，桶冻结）
        simulator.setRuleMode(DownstreamSimulator.RuleMode.TIMEOUT);
        profileService.transition(profileService.get(dep), ProfileState.DOWN, 0,
                CapacityEventType.ENTER_DOWN, "测试熔断");
        probe.probeTick();
        throttler.recoveryTick();
        assertEquals(ProfileState.DOWN, profileService.get(dep).getState(), "探测失败：保持 DOWN");

        // 健康信号短暂恢复：单次探测成功不足以进入恢复
        simulator.setRuleMode(DownstreamSimulator.RuleMode.NORMAL);
        probe.probeTick();
        throttler.recoveryTick();
        assertEquals(ProfileState.DOWN, profileService.get(dep).getState(),
                "保守恢复：单次恢复信号不被追认（需连续 2 次探测成功）");
        assertEquals(0, bucketService.get(dep).getRefillPerTick(), "桶保持冻结零补充");

        // 信号再次失败：连续计数清零
        simulator.setRuleMode(DownstreamSimulator.RuleMode.TIMEOUT);
        probe.probeTick();
        throttler.recoveryTick();
        assertEquals(0, profileService.get(dep).getConsecutiveProbeOk(), "抖动后连续成功计数清零");
        assertEquals(ProfileState.DOWN, profileService.get(dep).getState());

        // 持续恢复：连续 2 次探测成功 → RECOVERING（爬坡起点 1/周期）
        simulator.setRuleMode(DownstreamSimulator.RuleMode.NORMAL);
        probe.probeTick();
        throttler.recoveryTick();
        assertEquals(ProfileState.DOWN, profileService.get(dep).getState(), "第 1 次成功仍不放开");
        probe.probeTick();
        throttler.recoveryTick();
        CapacityProfile recovering = profileService.get(dep);
        assertEquals(ProfileState.RECOVERING, recovering.getState());
        assertEquals(1, recovering.getEffectiveCeiling(), "保守爬坡：从 1/周期起步而非声明上限 2");
        assertEquals(1, bucketService.get(dep).getRefillPerTick(), "桶补充速率同步为爬坡速率");

        // 再稳定一个周期 → 爬坡到达声明上限 → NORMAL
        probe.probeTick();
        throttler.recoveryTick();
        CapacityProfile recovered = profileService.get(dep);
        assertEquals(ProfileState.NORMAL, recovered.getState());
        assertEquals(recovered.getDeclaredCeiling(), recovered.getEffectiveCeiling(),
                "恢复后生效上限 = 声明上限（爬坡全程未越界）");
        assertEquals(2, bucketService.get(dep).getRefillPerTick());
    }

    @Test
    void databaseProbeUsesRealSelectOne() {
        // 清算数据库画像熔断后，健康探针对其执行真实 SELECT 1 探测
        com.example.clearing.domain.CapacityProfile db = profileService.get(DependencyHealthProbe.DEP_DB);
        profileService.transition(db, com.example.clearing.domain.ProfileState.DOWN, 0,
                com.example.clearing.domain.CapacityEventType.ENTER_DOWN, "测试：数据库画像熔断");
        int probes = probe.probeTick();
        assertEquals(1, probes, "DOWN 的清算库每周期恰好被探测 1 次（探测上限）");
        com.example.clearing.domain.CapacityProfile after = profileService.get(DependencyHealthProbe.DEP_DB);
        assertEquals(1, after.getTotalProbes());
        assertEquals(1, after.getConsecutiveProbeOk(), "数据库连接正常时 SELECT 1 探测成功");
    }

    @Test
    void probeFailureDuringRampAbortsBackToDown() {
        String dep = TaskRegistrar.DEP_RULE;
        simulator.setRuleMode(DownstreamSimulator.RuleMode.TIMEOUT);
        profileService.transition(profileService.get(dep), ProfileState.DOWN, 0,
                CapacityEventType.ENTER_DOWN, "测试熔断");
        // 连续 2 次探测成功进入恢复爬坡
        simulator.setRuleMode(DownstreamSimulator.RuleMode.NORMAL);
        probe.probeTick();
        throttler.recoveryTick();
        probe.probeTick();
        throttler.recoveryTick();
        assertEquals(ProfileState.RECOVERING, profileService.get(dep).getState());

        // 爬坡期间探测失败 → 立即回落 DOWN（不追认短暂恢复）
        simulator.setRuleMode(DownstreamSimulator.RuleMode.TIMEOUT);
        probe.probeTick();
        throttler.recoveryTick();
        CapacityProfile aborted = profileService.get(dep);
        assertEquals(ProfileState.DOWN, aborted.getState(), "恢复期间探测失败 → 回落 DOWN");
        assertEquals(0, aborted.getEffectiveCeiling());
        assertEquals(0, bucketService.get(dep).getRefillPerTick(), "桶重新冻结");
    }
}
