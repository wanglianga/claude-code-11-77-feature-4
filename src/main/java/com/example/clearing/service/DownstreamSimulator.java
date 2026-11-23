package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.dto.BillPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 下游接口模拟器（院内联调桩）：
 *  - 待遇规则服务：NORMAL / TIMEOUT（模拟短暂不可用时的调用超时）
 *  - 基金网关：NORMAL / DELAYED（受理成功但确认回执延迟）/ DOWN
 * 依赖健康度由依赖状态图维护；本类只模拟“调用时”的行为与回执时序。
 */
@Component
public class DownstreamSimulator {

    public enum RuleMode {
        NORMAL,
        TIMEOUT
    }

    public enum FundMode {
        NORMAL,
        /** 受理成功，回执延迟 delaySeconds 秒 */
        DELAYED,
        DOWN
    }

    public enum FundCallResult {
        /** 受理且回执即时返回 */
        RECEIPT_READY,
        /** 受理成功，回执延迟 */
        ACCEPTED_PENDING
    }

    private final ObjectMapper objectMapper;

    private volatile RuleMode ruleMode = RuleMode.NORMAL;
    private volatile FundMode fundMode = FundMode.NORMAL;
    private volatile int fundDelaySeconds = 300;

    /** 基金网关回执就绪时间：operationNo -> readyAt */
    private final Map<String, Instant> receipts = new ConcurrentHashMap<>();
    /** 人工确认的回执（模拟对账后人工补确认） */
    private final Set<String> manualConfirmed = ConcurrentHashMap.newKeySet();

    public DownstreamSimulator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BillPayload parse(String payload) {
        try {
            BillPayload p = objectMapper.readValue(payload, BillPayload.class);
            if (p.getItems() == null || p.getItems().isEmpty()) {
                throw new BadPayloadException("报文缺少结算明细 items");
            }
            return p;
        } catch (BadPayloadException e) {
            throw e;
        } catch (Exception e) {
            throw new BadPayloadException("报文不是合法 JSON: " + e.getMessage());
        }
    }

    /** 待遇规则计算：返回统筹支付金额（演示口径：总金额 × 0.7） */
    public BigDecimal callRuleEngine(ClearingTask task, BillPayload payload) {
        if (ruleMode == RuleMode.TIMEOUT) {
            throw new TransientFailureException("待遇规则服务调用超时（模拟接口降级）");
        }
        if (payload.isForceRuleConflict()) {
            throw new RuleConflictException("清单同时命中门诊统筹与慢病专项规则，待遇互斥，需人工裁定");
        }
        BigDecimal total = payload.getItems().stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.multiply(new BigDecimal("0.7")).setScale(2, RoundingMode.HALF_UP);
    }

    /** 基金回传：按操作号受理；DELAYED 模式下回执延迟 */
    public FundCallResult callFundGateway(String operationNo) {
        switch (fundMode) {
            case DOWN:
                throw new TransientFailureException("基金网关连接失败（模拟接口降级）");
            case DELAYED:
                receipts.putIfAbsent(operationNo, Instant.now().plusSeconds(fundDelaySeconds));
                return FundCallResult.ACCEPTED_PENDING;
            case NORMAL:
            default:
                receipts.put(operationNo, Instant.now());
                return FundCallResult.RECEIPT_READY;
        }
    }

    /** 按操作号查询回执（不消耗扣款令牌） */
    public boolean queryReceipt(String operationNo) {
        if (manualConfirmed.contains(operationNo)) {
            return true;
        }
        if (fundMode == FundMode.DOWN) {
            throw new TransientFailureException("基金网关连接失败，回执查询未完成");
        }
        Instant readyAt = receipts.get(operationNo);
        if (readyAt == null) {
            if (fundMode == FundMode.NORMAL) {
                receipts.put(operationNo, Instant.now());
                return true;
            }
            return false;
        }
        return !Instant.now().isBefore(readyAt);
    }

    public void confirmManually(String operationNo) {
        manualConfirmed.add(operationNo);
        receipts.put(operationNo, Instant.now());
    }

    /** 健康探针：待遇规则服务轻量探测（不产生业务调用，不消耗令牌） */
    public boolean pingRule() {
        return ruleMode == RuleMode.NORMAL;
    }

    /** 健康探针：基金网关轻量探测（DOWN 视为不可达；DELAYED 仍可受理） */
    public boolean pingFund() {
        return fundMode != FundMode.DOWN;
    }

    public RuleMode getRuleMode() {
        return ruleMode;
    }

    public void setRuleMode(RuleMode ruleMode) {
        this.ruleMode = ruleMode;
    }

    public FundMode getFundMode() {
        return fundMode;
    }

    public void setFundMode(FundMode fundMode) {
        this.fundMode = fundMode;
    }

    public int getFundDelaySeconds() {
        return fundDelaySeconds;
    }

    public void setFundDelaySeconds(int fundDelaySeconds) {
        this.fundDelaySeconds = fundDelaySeconds;
    }

    public void reset() {
        ruleMode = RuleMode.NORMAL;
        fundMode = FundMode.NORMAL;
        fundDelaySeconds = 300;
        receipts.clear();
        manualConfirmed.clear();
    }
}
