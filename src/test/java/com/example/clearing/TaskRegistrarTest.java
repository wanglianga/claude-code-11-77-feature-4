package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.clearing.domain.FailureType;
import com.example.clearing.domain.SourceType;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.FailureRecordRepository;
import com.example.clearing.service.DemoScenarioService;
import com.example.clearing.service.TaskRegistrar;
import com.example.clearing.service.TokenBucketService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 登记器不变量：同键同内容幂等吸收；同键不同内容 → 内容冲突且不发放令牌 */
@SpringBootTest
class TaskRegistrarTest {

    @Autowired
    private TaskRegistrar registrar;

    @Autowired
    private TokenBucketService buckets;

    @Autowired
    private FailureRecordRepository failures;

    @Autowired
    private DemoScenarioService demo;

    @BeforeEach
    void reset() {
        demo.reset();
    }

    @Test
    void duplicateRegistrationIsAbsorbed() {
        String payload = "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":10}]}";
        var first = registrar.register("B1", LocalDate.of(2026, 11, 23), "K1", "v1",
                SourceType.HOSPITAL, new BigDecimal("100"), payload);
        var second = registrar.register("B2", LocalDate.of(2026, 11, 23), "K1", "v1",
                SourceType.HOSPITAL, new BigDecimal("100"), payload);
        assertEquals(TaskRegistrar.Outcome.CREATED, first.outcome());
        assertEquals(TaskRegistrar.Outcome.DUPLICATE, second.outcome());
        assertEquals(first.task().getId(), second.task().getId(), "重复登记返回同一任务");
    }

    @Test
    void sameKeyDifferentContentYieldsConflictAndNoToken() {
        var first = registrar.register("B1", LocalDate.of(2026, 11, 23), "K2", "v1",
                SourceType.HOSPITAL, new BigDecimal("100"),
                "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":10}]}");
        var conflict = registrar.register("B2", LocalDate.of(2026, 11, 23), "K2", "v1",
                SourceType.HOSPITAL, new BigDecimal("100"),
                "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":2,\"price\":10}]}");
        assertEquals(TaskRegistrar.Outcome.CREATED, first.outcome());
        assertEquals(TaskRegistrar.Outcome.CONTENT_CONFLICT, conflict.outcome());
        assertEquals(TaskStatus.CONTENT_CONFLICT, conflict.task().getStatus());
        assertEquals(0, buckets.get("RULE_ENGINE").getTotalConsumed(), "内容冲突不发放令牌");
        assertEquals(0, buckets.get("FUND_GW").getTotalConsumed(), "内容冲突不发放令牌");
        assertTrue(failures.findAll().stream()
                .anyMatch(f -> f.getFailureType() == FailureType.CONTENT_MISMATCH),
                "失败判定器记录 CONTENT_MISMATCH");
    }
}
