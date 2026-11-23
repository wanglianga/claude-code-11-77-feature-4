package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.QuotaGrant;
import com.example.clearing.domain.SourceType;
import com.example.clearing.repo.QuotaGrantRepository;
import com.example.clearing.service.DemoScenarioService;
import com.example.clearing.service.QueueQuotaController;
import com.example.clearing.service.QueueQuotaController.Admission;
import com.example.clearing.service.TaskRegistrar;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 队列配额控制器：每来源最低服务份额 + 风险序公共池 + 保留份额不可侵占 */
@SpringBootTest
class QueueQuotaControllerTest {

    @Autowired
    private QueueQuotaController quotaController;

    @Autowired
    private QuotaGrantRepository quotaRepository;

    @Autowired
    private DemoScenarioService demo;

    @BeforeEach
    void reset() {
        demo.reset();
    }

    @Test
    void reservedShareProtectsLowRiskSource_bonusPoolServesHighRisk() {
        List<ClearingTask> waiting = List.of(
                task("H1", SourceType.HOSPITAL, 90000, 9.0),
                task("H2", SourceType.HOSPITAL, 90000, 9.0),
                task("H3", SourceType.HOSPITAL, 90000, 9.0),
                task("P1", SourceType.PHARMACY, 500, 1.0),
                task("P2", SourceType.PHARMACY, 500, 1.0));
        quotaController.beginTick();
        quotaController.planFor(TaskRegistrar.DEP_RULE, 4, waiting);

        // 槽位 4 = 医院保留 1 + 药店保留 1 + 公共池 2
        assertEquals(Admission.RESERVED, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(0)));
        assertEquals(Admission.BONUS, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(1)));
        assertEquals(Admission.BONUS, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(2)));
        assertEquals(Admission.RESERVED, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(3)));
        assertEquals(Admission.DENIED, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(4)),
                "药店第二笔：保留已用、公共池被高风险耗尽 → 拒绝，但不影响其保留份额");

        quotaController.flushGrants();
        QuotaGrant hosp = grantOf("HOSPITAL");
        QuotaGrant pharm = grantOf("PHARMACY");
        assertEquals(1, hosp.getReservedGranted());
        assertEquals(2, hosp.getBonusGranted(), "公共池授予可追溯");
        assertEquals(9.0, hosp.getMaxRiskScore());
        assertEquals(1, pharm.getReservedGranted(), "低风险来源保留份额不被侵占");
        assertEquals(0, pharm.getBonusGranted());
    }

    @Test
    void tightCapacityStillGuaranteesFloorPerSource() {
        List<ClearingTask> waiting = List.of(
                task("H1", SourceType.HOSPITAL, 90000, 9.0),
                task("H2", SourceType.HOSPITAL, 90000, 9.0),
                task("P1", SourceType.PHARMACY, 500, 1.0));
        quotaController.beginTick();
        quotaController.planFor(TaskRegistrar.DEP_RULE, 2, waiting);

        // 槽位 2 = 医院保留 1 + 药店保留 1，公共池 0：高风险不能独占
        assertEquals(Admission.RESERVED, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(0)));
        assertEquals(Admission.DENIED, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(1)),
                "公共池为空，医院第二笔被拒（高优先级不能独占）");
        assertEquals(Admission.RESERVED, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(2)),
                "药店保留份额不受医院需求影响");
    }

    @Test
    void singleSourceUsesReservedThenBonus() {
        List<ClearingTask> waiting = List.of(
                task("H1", SourceType.HOSPITAL, 1000, 1.0),
                task("H2", SourceType.HOSPITAL, 1000, 1.0),
                task("H3", SourceType.HOSPITAL, 1000, 1.0));
        quotaController.beginTick();
        quotaController.planFor(TaskRegistrar.DEP_RULE, 3, waiting);
        assertEquals(Admission.RESERVED, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(0)));
        assertEquals(Admission.BONUS, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(1)));
        assertEquals(Admission.BONUS, quotaController.admit(TaskRegistrar.DEP_RULE, waiting.get(2)));
    }

    private QuotaGrant grantOf(String sourceType) {
        return quotaRepository.findAll().stream()
                .filter(q -> q.getDependencyCode().equals(TaskRegistrar.DEP_RULE))
                .filter(q -> q.getSourceType().equals(sourceType))
                .findFirst().orElseThrow();
    }

    private ClearingTask task(String key, SourceType source, double amount, double riskScore) {
        ClearingTask task = new ClearingTask();
        task.setBusinessKey(key);
        task.setSourceType(source);
        task.setAmount(BigDecimal.valueOf(amount));
        task.setPriority(TaskRegistrar.priorityOf(BigDecimal.valueOf(amount)));
        task.setRiskScore(riskScore);
        task.setCreatedAt(Instant.now());
        return task;
    }
}
