package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.DepHealth;
import com.example.clearing.domain.EntryStatus;
import com.example.clearing.domain.SourceType;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.LedgerEntryRepository;
import com.example.clearing.service.CatalogService;
import com.example.clearing.service.ClearingScheduler;
import com.example.clearing.service.DemoScenarioService;
import com.example.clearing.service.DependencyGraphService;
import com.example.clearing.service.DownstreamSimulator;
import com.example.clearing.service.LedgerService;
import com.example.clearing.service.TaskRegistrar;
import com.example.clearing.service.TokenBucketService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 调度器端到端不变量 */
@SpringBootTest
class SchedulerFlowTest {

    @Autowired
    private ClearingScheduler scheduler;

    @Autowired
    private TaskRegistrar registrar;

    @Autowired
    private TokenBucketService buckets;

    @Autowired
    private DependencyGraphService graph;

    @Autowired
    private DownstreamSimulator simulator;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ClearingTaskRepository taskRepository;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @Autowired
    private DemoScenarioService demo;

    @BeforeEach
    void reset() {
        demo.reset();
    }

    private ClearingTask reg(String key, String payload) {
        return registrar.register("B1", LocalDate.of(2026, 11, 23), key, "v1",
                SourceType.HOSPITAL, new BigDecimal("1000.00"), payload).task();
    }

    private TaskStatus statusOf(Long id) {
        return taskRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void happyPathCalcAndReturnInOneTick() {
        ClearingTask task = reg("FLOW-1", "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":100}]}");
        scheduler.tick();
        assertEquals(TaskStatus.CONFIRMED, statusOf(task.getId()));
        assertEquals(2, ledgerRepository.findAll().size(), "1 扣款 + 1 回传");
    }

    @Test
    void fundDelayedReceiptKeepsPendingAndConsumesNoNewToken() {
        simulator.setFundMode(DownstreamSimulator.FundMode.DELAYED);
        simulator.setFundDelaySeconds(600);
        ClearingTask task = reg("FLOW-2", "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":100}]}");
        scheduler.tick();
        assertEquals(TaskStatus.PENDING_CONFIRM, statusOf(task.getId()), "回执延迟 → 待确认");
        long consumed = buckets.get(TaskRegistrar.DEP_FUND).getTotalConsumed();

        scheduler.tick();
        scheduler.tick();
        assertEquals(consumed, buckets.get(TaskRegistrar.DEP_FUND).getTotalConsumed(),
                "待确认期间不取得新的扣款令牌");
        assertEquals(TaskStatus.PENDING_CONFIRM, statusOf(task.getId()));
        assertEquals(2, ledgerRepository.findAll().size(), "无重复入账");

        String opNo = LedgerService.fundReturnOpNo(taskRepository.findById(task.getId()).orElseThrow());
        simulator.confirmManually(opNo);
        scheduler.tick();
        assertEquals(TaskStatus.CONFIRMED, statusOf(task.getId()));
        assertEquals(EntryStatus.CONFIRMED,
                ledgerRepository.findByOperationNo(opNo).orElseThrow().getStatus());
    }

    @Test
    void ruleEngineDownFreezesBucket_recoveryDoesNotBackfill() {
        reg("FLOW-3A", "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":10}]}");
        reg("FLOW-3B", "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":10}]}");
        scheduler.tick();
        assertEquals(4, buckets.get(TaskRegistrar.DEP_RULE).getTokens());

        graph.updateHealth(TaskRegistrar.DEP_RULE, DepHealth.DOWN, "测试降级");
        reg("FLOW-3C", "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":10}]}");
        reg("FLOW-3D", "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":10}]}");
        scheduler.tick();
        scheduler.tick();
        assertEquals(4, buckets.get(TaskRegistrar.DEP_RULE).getTokens(), "故障期间桶冻结不补充");
        assertEquals(0, buckets.get(TaskRegistrar.DEP_RULE).getTotalRefilled());
        assertEquals(TaskStatus.READY, statusOf(taskIdOf("FLOW-3C")), "依赖 DOWN 时任务保持就绪等待");

        graph.updateHealth(TaskRegistrar.DEP_RULE, DepHealth.HEALTHY, "测试恢复");
        scheduler.tick();
        assertEquals(2, buckets.get(TaskRegistrar.DEP_RULE).getTotalRefilled(),
                "恢复后仅补充一个周期，不补发故障期间尝试");
    }

    private Long taskIdOf(String businessKey) {
        return taskRepository.findAll().stream()
                .filter(t -> t.getBusinessKey().equals(businessKey))
                .findFirst().orElseThrow().getId();
    }

    @Test
    void catalogMissingWaitsThenRecoversAfterSync() {
        ClearingTask task = reg("FLOW-4", "{\"items\":[{\"catalogCode\":\"ZZZ-1\",\"qty\":1,\"price\":10}]}");
        scheduler.tick();
        assertEquals(TaskStatus.WAITING_DEPENDENCY, statusOf(task.getId()));
        assertEquals(0, buckets.get(TaskRegistrar.DEP_RULE).getTotalConsumed(), "目录等待不消耗令牌");

        catalogService.add("ZZZ-1", "测试药品", "2026.11");
        scheduler.tick();
        assertEquals(TaskStatus.CONFIRMED, statusOf(task.getId()));
    }

    @Test
    void ruleConflictEscalatesToManualReview() {
        ClearingTask task = reg("FLOW-5",
                "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":10}],\"forceRuleConflict\":true}");
        scheduler.tick();
        assertEquals(TaskStatus.MANUAL_REVIEW, statusOf(task.getId()));
        assertTrue(taskRepository.findById(task.getId()).orElseThrow().getLastReason().contains("规则冲突"));
    }
}
