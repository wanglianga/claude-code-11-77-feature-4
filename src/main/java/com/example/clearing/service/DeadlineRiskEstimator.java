package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.ClearingTask;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

/**
 * 截止风险估计器：把"金额风险"与"距清算截止的紧迫度"折算成可复核的风险分。
 * 风险分 = 金额权重（高 3 / 中 2 / 低 1）× 临期系数（逾期 4 / 临期窗口内 3 / 24h 内 2 / 其余 1）。
 * 风险分写入任务（riskScore/riskBand），调度排序、配额授予与决策事件都引用它，
 * 使高金额临期任务的优先是可追溯的，而不是隐式插队。
 */
@Service
public class DeadlineRiskEstimator {

    public static final String BAND_CRITICAL = "CRITICAL";
    public static final String BAND_HIGH = "HIGH";
    public static final String BAND_MEDIUM = "MEDIUM";
    public static final String BAND_LOW = "LOW";

    private final ClearingProperties properties;

    public DeadlineRiskEstimator(ClearingProperties properties) {
        this.properties = properties;
    }

    /** 缺省截止：清算日当日截止时刻（本地时区） */
    public Instant defaultDeadline(java.time.LocalDate clearingDate) {
        int hour = properties.getCapacity().getDeadline().getCutoffHour();
        return clearingDate.atTime(LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant();
    }

    /** 重新评估任务风险分并写回任务实体（调用方负责保存） */
    public void assess(ClearingTask task, Instant now) {
        Instant deadline = task.getDeadlineAt() != null ? task.getDeadlineAt()
                : defaultDeadline(task.getClearingDate());
        double urgency = urgencyOf(deadline, now);
        double amountWeight = switch (task.getPriority()) {
            case 2 -> 3.0;
            case 1 -> 2.0;
            default -> 1.0;
        };
        double score = amountWeight * urgency;
        task.setRiskScore(score);
        task.setRiskBand(bandOf(score));
    }

    /** 临期系数：逾期 4；临期窗口内 3；24 小时内 2；其余 1 */
    public double urgencyOf(Instant deadline, Instant now) {
        long minutesLeft = Duration.between(now, deadline).toMinutes();
        if (minutesLeft < 0) {
            return 4.0;
        }
        if (minutesLeft <= properties.getCapacity().getDeadline().getUrgentWindowMinutes()) {
            return 3.0;
        }
        if (minutesLeft <= 24 * 60) {
            return 2.0;
        }
        return 1.0;
    }

    public String bandOf(double score) {
        if (score >= 9.0) {
            return BAND_CRITICAL;
        }
        if (score >= 6.0) {
            return BAND_HIGH;
        }
        if (score >= 3.0) {
            return BAND_MEDIUM;
        }
        return BAND_LOW;
    }

    /** 人话版风险依据，写入决策事件，保证优先级可追溯 */
    public String explain(ClearingTask task) {
        Instant deadline = task.getDeadlineAt() != null ? task.getDeadlineAt()
                : defaultDeadline(task.getClearingDate());
        String amountPart = switch (task.getPriority()) {
            case 2 -> "金额带HIGH(×3)";
            case 1 -> "金额带MEDIUM(×2)";
            default -> "金额带LOW(×1)";
        };
        long minutesLeft = Duration.between(Instant.now(), deadline).toMinutes();
        String urgencyPart = switch ((int) urgencyOf(deadline, Instant.now())) {
            case 4 -> "已逾期(×4)";
            case 3 -> "临期(×3，距截止 " + minutesLeft + " 分钟)";
            case 2 -> "24h内(×2)";
            default -> "宽裕(×1)";
        };
        return "风险分 " + task.getRiskScore() + "（" + amountPart + " × " + urgencyPart
                + "，截止 " + deadline + "，风险带 " + task.getRiskBand() + "）";
    }
}
