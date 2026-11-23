package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.SourceType;
import com.example.clearing.service.DeadlineRiskEstimator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** 截止风险估计器：金额权重 × 临期系数 → 可追溯风险分 */
class DeadlineRiskEstimatorTest {

    private final ClearingProperties properties = new ClearingProperties();
    private final DeadlineRiskEstimator estimator = new DeadlineRiskEstimator(properties);

    @Test
    void urgencyTiersFollowDeadlineDistance() {
        Instant now = Instant.now();
        assertEquals(4.0, estimator.urgencyOf(now.minus(1, ChronoUnit.MINUTES), now), "已逾期 ×4");
        assertEquals(3.0, estimator.urgencyOf(now.plus(30, ChronoUnit.MINUTES), now), "临期窗口内 ×3");
        assertEquals(2.0, estimator.urgencyOf(now.plus(10, ChronoUnit.HOURS), now), "24h 内 ×2");
        assertEquals(1.0, estimator.urgencyOf(now.plus(3, ChronoUnit.DAYS), now), "宽裕 ×1");
    }

    @Test
    void riskScoreCombinesAmountWeightAndUrgency() {
        ClearingTask urgent = task(90000, Instant.now().plus(30, ChronoUnit.MINUTES));
        estimator.assess(urgent, Instant.now());
        assertEquals(9.0, urgent.getRiskScore(), "高金额 × 临期 = 3×3");
        assertEquals(DeadlineRiskEstimator.BAND_CRITICAL, urgent.getRiskBand());

        ClearingTask calm = task(90000, Instant.now().plus(3, ChronoUnit.DAYS));
        estimator.assess(calm, Instant.now());
        assertEquals(3.0, calm.getRiskScore(), "高金额 × 宽裕 = 3×1");
        assertEquals(DeadlineRiskEstimator.BAND_MEDIUM, calm.getRiskBand());

        ClearingTask lowOverdue = task(500, Instant.now().minus(5, ChronoUnit.MINUTES));
        estimator.assess(lowOverdue, Instant.now());
        assertEquals(4.0, lowOverdue.getRiskScore(), "低金额 × 逾期 = 1×4");
        assertEquals(DeadlineRiskEstimator.BAND_MEDIUM, lowOverdue.getRiskBand());
    }

    @Test
    void defaultDeadlineIsCutoffHourOfClearingDate() {
        Instant deadline = estimator.defaultDeadline(LocalDate.of(2026, 11, 30));
        assertTrue(deadline.toString().startsWith("2026-11-30"), "缺省截止为清算日当日");
    }

    private ClearingTask task(double amount, Instant deadline) {
        ClearingTask task = new ClearingTask();
        task.setSourceType(SourceType.HOSPITAL);
        task.setAmount(BigDecimal.valueOf(amount));
        task.setPriority(com.example.clearing.service.TaskRegistrar.priorityOf(BigDecimal.valueOf(amount)));
        task.setClearingDate(LocalDate.of(2026, 11, 30));
        task.setDeadlineAt(deadline);
        return task;
    }
}
