package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.clearing.domain.CapacityEventType;
import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.domain.ProfileState;
import com.example.clearing.repo.CapacityEventRepository;
import com.example.clearing.service.CapacityProfileService;
import com.example.clearing.service.DemoScenarioService;
import com.example.clearing.service.DependencyHealthProbe;
import com.example.clearing.service.DownstreamSimulator;
import com.example.clearing.service.TaskRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 探测上限：全局每周期探测总次数受限，超预算的探测被跳过并留痕 */
@SpringBootTest(properties = "clearing.capacity.probe.max-per-tick-total=1")
class ProbeBudgetCapTest {

    @Autowired
    private DependencyHealthProbe probe;

    @Autowired
    private CapacityProfileService profileService;

    @Autowired
    private CapacityEventRepository eventRepository;

    @Autowired
    private DownstreamSimulator simulator;

    @Autowired
    private DemoScenarioService demo;

    @BeforeEach
    void reset() {
        demo.reset();
    }

    @Test
    void globalProbeBudgetCapsProbesPerTick() {
        // 两个依赖同时熔断，全局探测预算 = 1/周期
        simulator.setRuleMode(DownstreamSimulator.RuleMode.TIMEOUT);
        simulator.setFundMode(DownstreamSimulator.FundMode.DOWN);
        down(TaskRegistrar.DEP_RULE);
        down(TaskRegistrar.DEP_FUND);

        int executed = probe.probeTick();

        assertEquals(1, executed, "全局探测预算 1/周期：两个 DOWN 依赖只执行 1 次探测");
        long skipped = eventRepository.findAll().stream()
                .filter(e -> e.getEventType() == CapacityEventType.PROBE_SKIPPED_BUDGET)
                .count();
        assertEquals(1, skipped, "超出预算的探测被跳过并记录 PROBE_SKIPPED_BUDGET");
        long probes = eventRepository.findAll().stream()
                .filter(e -> e.getEventType() == CapacityEventType.PROBE_OK
                        || e.getEventType() == CapacityEventType.PROBE_FAIL)
                .count();
        assertEquals(1, probes, "本周期实际探测事件恰好 1 条");
        long totalProbes = profileService.list().stream().mapToLong(CapacityProfile::getTotalProbes).sum();
        assertTrue(totalProbes <= 1, "探测总量不超过预算上限");
    }

    private void down(String dep) {
        CapacityProfile profile = profileService.get(dep);
        profileService.transition(profile, ProfileState.DOWN, 0, CapacityEventType.ENTER_DOWN, "测试熔断");
    }
}
