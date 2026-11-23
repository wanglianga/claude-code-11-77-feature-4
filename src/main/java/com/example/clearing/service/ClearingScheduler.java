package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.EntryStatus;
import com.example.clearing.domain.LedgerEntry;
import com.example.clearing.domain.Stage;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.dto.BillPayload;
import com.example.clearing.dto.TickResult;
import com.example.clearing.repo.ClearingTaskRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调度器：一个调度周期的管线——
 * 依赖健康探针（探测上限内）→ 降级编排（局部限流）→ 恢复节流（保守爬坡）→
 * 截止风险刷新 → 本地桶补充（生效上限即补充速率）→ 队列配额计划 →
 * 等待者提升 → 待遇计算投放（配额 + RULE 令牌 + 清算库令牌）→ 基金回传投放 →
 * 待确认回执查询（免费，不消耗扣款令牌）→ 配额证据落库 → 风暴观测。
 * 投放仍需同时满足：配额准入、令牌充足、退避时间已到、依赖健康。
 */
@Service
public class ClearingScheduler {

    private final ClearingTaskRepository taskRepository;
    private final TokenBucketService bucketService;
    private final DependencyHealthProbe healthProbe;
    private final DegradationOrchestrator orchestrator;
    private final RecoveryThrottler recoveryThrottler;
    private final QueueQuotaController quotaController;
    private final DeadlineRiskEstimator riskEstimator;
    private final StormObserver stormObserver;
    private final DependencyGraphService graphService;
    private final DownstreamSimulator simulator;
    private final CatalogService catalogService;
    private final LedgerService ledgerService;
    private final FailureClassifier failureClassifier;
    private final EventLog eventLog;

    public ClearingScheduler(ClearingTaskRepository taskRepository,
            TokenBucketService bucketService,
            DependencyHealthProbe healthProbe,
            DegradationOrchestrator orchestrator,
            RecoveryThrottler recoveryThrottler,
            QueueQuotaController quotaController,
            DeadlineRiskEstimator riskEstimator,
            StormObserver stormObserver,
            DependencyGraphService graphService,
            DownstreamSimulator simulator,
            CatalogService catalogService,
            LedgerService ledgerService,
            FailureClassifier failureClassifier,
            EventLog eventLog) {
        this.taskRepository = taskRepository;
        this.bucketService = bucketService;
        this.healthProbe = healthProbe;
        this.orchestrator = orchestrator;
        this.recoveryThrottler = recoveryThrottler;
        this.quotaController = quotaController;
        this.riskEstimator = riskEstimator;
        this.stormObserver = stormObserver;
        this.graphService = graphService;
        this.simulator = simulator;
        this.catalogService = catalogService;
        this.ledgerService = ledgerService;
        this.failureClassifier = failureClassifier;
        this.eventLog = eventLog;
    }

    @Transactional
    public TickResult tick() {
        TickResult result = new TickResult();
        healthProbe.probeTick();
        orchestrator.orchestrateTick();
        recoveryThrottler.recoveryTick();
        refreshRiskScores();
        bucketService.refillAll();
        quotaController.beginTick();
        promoteWaiters(result);
        dispatchCalc(result);
        dispatchReturn(result);
        pendingConfirmPass(result);
        quotaController.flushGrants();
        stormObserver.observe();
        return result;
    }

    /** 截止风险估计器：刷新等待中任务的风险分（临期系数随时间变化），供本周期排序与配额使用 */
    private void refreshRiskScores() {
        Instant now = Instant.now();
        for (ClearingTask task : taskRepository.findByStatusIn(
                List.of(TaskStatus.READY, TaskStatus.RETRY_SCHEDULED))) {
            riskEstimator.assess(task, now);
            taskRepository.save(task);
        }
    }

    /** 目录等待的任务：目录恢复且目录项齐全后重新就绪 */
    private void promoteWaiters(TickResult result) {
        if (graphService.isDown("CATALOG")) {
            return;
        }
        for (ClearingTask task : taskRepository.findByStatus(TaskStatus.WAITING_DEPENDENCY)) {
            BillPayload payload = simulator.parse(task.getPayload());
            List<String> missing = catalogService.missingCodes(payload);
            if (missing.isEmpty()) {
                task.setStatus(TaskStatus.READY);
                task.setNextAttemptAt(Instant.now());
                eventLog.record(task, Decision.PROMOTED_READY,
                        "目录已恢复（当前目录版本 " + catalogService.currentVersion() + "），目录匹配通过，重新进入就绪队列");
                taskRepository.save(task);
                result.promoted++;
            }
        }
    }

    /** 待遇计算阶段：队列配额准入 + RULE_ENGINE 令牌 + 清算库令牌（保护数据库入账） */
    private void dispatchCalc(TickResult result) {
        String dep = TaskRegistrar.DEP_RULE;
        String dbDep = DependencyHealthProbe.DEP_DB;
        boolean depDown = graphService.isDown(dep);
        boolean dbDown = graphService.isDown(dbDep);
        boolean catalogDown = graphService.isDown("CATALOG");
        List<ClearingTask> eligible = eligible(Stage.CALC, dep);
        quotaController.planFor(dep, bucketService.available(dep), eligible);
        for (ClearingTask task : eligible) {
            if (depDown) {
                recordIfChanged(task, Decision.WAIT_DEPENDENCY_DOWN,
                        "待遇规则服务 DOWN：依赖 " + dep + " 本地令牌桶已冻结（不放行、不补充），任务等待依赖恢复；"
                                + "恢复后按剩余预算渐进投放，不补发故障期间的全部尝试");
                result.waitingDependency++;
                continue;
            }
            BillPayload payload = simulator.parse(task.getPayload());
            if (catalogDown) {
                failureClassifier.onCatalogWait(task, "目录服务不可用（CATALOG DOWN），等待目录恢复");
                result.waitingDependency++;
                continue;
            }
            List<String> missing = catalogService.missingCodes(payload);
            if (!missing.isEmpty()) {
                failureClassifier.onCatalogWait(task, "目录项缺失 " + missing
                        + "，等待目录同步（当前目录版本 " + catalogService.currentVersion() + "）");
                result.waitingDependency++;
                continue;
            }
            if (dbDown) {
                recordIfChanged(task, Decision.WAIT_DEPENDENCY_DOWN,
                        "清算数据库 DOWN：账本写入暂停（局部限流仅作用于 " + dbDep
                                + "），任务等待数据库恢复；基金/规则队列不受影响");
                result.waitingDependency++;
                continue;
            }
            QueueQuotaController.Admission admission = quotaController.admit(dep, task);
            if (admission == QueueQuotaController.Admission.DENIED) {
                recordIfChanged(task, Decision.THROTTLED,
                        "队列配额耗尽：来源 " + task.getSourceType() + " 的保留份额与本周期公共池均已分配完毕"
                                + "（每来源最低服务份额保护其他来源不被饿死），等待下一周期；" + riskEstimator.explain(task));
                result.throttled++;
                result.quotaDenied++;
                continue;
            }
            if (!bucketService.tryConsume(dep)) {
                recordIfChanged(task, Decision.THROTTLED,
                        "依赖 " + dep + " 本地令牌桶余额不足，按预算等待下一周期补充；" + riskEstimator.explain(task));
                result.throttled++;
                continue;
            }
            if (!bucketService.tryConsume(dbDep)) {
                recordIfChanged(task, Decision.THROTTLED,
                        "清算数据库本地令牌桶余额不足：保护数据库入账写入，按预算等待下一周期补充");
                result.throttled++;
                continue;
            }
            doCalc(task, payload, admission, result);
        }
    }

    /** 基金回传阶段：队列配额准入 + FUND_GW 令牌（保护基金接口） */
    private void dispatchReturn(TickResult result) {
        String dep = TaskRegistrar.DEP_FUND;
        boolean depDown = graphService.isDown(dep);
        List<ClearingTask> eligible = eligible(Stage.RETURN, dep);
        quotaController.planFor(dep, bucketService.available(dep), eligible);
        for (ClearingTask task : eligible) {
            if (depDown) {
                recordIfChanged(task, Decision.WAIT_DEPENDENCY_DOWN,
                        "基金网关 DOWN：依赖 " + dep + " 本地令牌桶已冻结，回传暂停；其他依赖队列不受影响");
                result.waitingDependency++;
                continue;
            }
            QueueQuotaController.Admission admission = quotaController.admit(dep, task);
            if (admission == QueueQuotaController.Admission.DENIED) {
                recordIfChanged(task, Decision.THROTTLED,
                        "队列配额耗尽：来源 " + task.getSourceType() + " 的保留份额与本周期公共池均已分配完毕，"
                                + "等待下一周期；" + riskEstimator.explain(task));
                result.throttled++;
                result.quotaDenied++;
                continue;
            }
            if (!bucketService.tryConsume(dep)) {
                recordIfChanged(task, Decision.THROTTLED,
                        "基金接口容量受限：依赖 " + dep + " 本地令牌桶余额不足，按预算等待，保护基金接口");
                result.throttled++;
                continue;
            }
            doReturn(task, admission, result);
        }
    }

    /**
     * 待确认回执查询：基金回传已受理但回执延迟的任务，
     * 只按操作号查询回执，不消耗扣款令牌、不新建分录。
     */
    private void pendingConfirmPass(TickResult result) {
        for (ClearingTask task : taskRepository.findByStatus(TaskStatus.PENDING_CONFIRM)) {
            Optional<LedgerEntry> opt = ledgerService.fundReturnOf(task.getBusinessKey(), task.getBillVersion());
            if (opt.isEmpty()) {
                continue;
            }
            LedgerEntry entry = opt.get();
            try {
                if (simulator.queryReceipt(entry.getOperationNo())) {
                    ledgerService.confirm(entry.getOperationNo());
                    result.confirmed++;
                } else {
                    recordIfChanged(task, Decision.PENDING_CONFIRM,
                            "回执未到：账本保持待确认（操作号 " + entry.getOperationNo()
                                    + "），仅按操作号查询，不消耗扣款令牌");
                    result.waitingReceipt++;
                }
            } catch (TransientFailureException e) {
                orchestrator.reportOutcome(TaskRegistrar.DEP_FUND, false);
                recordIfChanged(task, Decision.RECEIPT_QUERY_FAILED,
                        "基金网关暂不可用，回执查询失败，账本保持待确认（操作号 " + entry.getOperationNo()
                                + "），不消耗令牌");
                result.waitingReceipt++;
            }
        }
    }

    private void doCalc(ClearingTask task, BillPayload payload,
            QueueQuotaController.Admission admission, TickResult result) {
        task.setStatus(TaskStatus.DISPATCHED);
        task.setAttemptCount(task.getAttemptCount() + 1);
        eventLog.record(task, Decision.DISPATCHED,
                "消耗 " + TaskRegistrar.DEP_RULE + " 令牌 1 + 清算库令牌 1（配额："
                        + quotaLabel(admission) + "；" + riskEstimator.explain(task) + "），开始目录匹配与待遇计算（第 "
                        + task.getAttemptCount() + " 次尝试）");
        taskRepository.save(task);
        try {
            BigDecimal amount = simulator.callRuleEngine(task, payload);
            orchestrator.reportOutcome(TaskRegistrar.DEP_RULE, true);
            LedgerEntry debit = ledgerService.postDebit(task, amount);
            task.setStage(Stage.RETURN);
            task.setDependencyCode(TaskRegistrar.DEP_FUND);
            task.setStatus(TaskStatus.READY);
            task.setNextAttemptAt(Instant.now());
            eventLog.record(task, Decision.STAGE_ADVANCED,
                    "待遇计算完成，统筹金额 " + amount + "，扣款分录 " + debit.getOperationNo()
                            + " 已入账（幂等，按清单版本+操作号约束），进入基金回传阶段");
            taskRepository.save(task);
            result.dispatched++;
        } catch (TransientFailureException e) {
            orchestrator.reportOutcome(TaskRegistrar.DEP_RULE, false);
            failureClassifier.onTransient(task, e.getMessage());
            result.failed++;
        } catch (RuleConflictException e) {
            failureClassifier.onRuleConflict(task, e.getMessage());
            result.escalated++;
        }
    }

    private void doReturn(ClearingTask task, QueueQuotaController.Admission admission, TickResult result) {
        task.setStatus(TaskStatus.DISPATCHED);
        task.setAttemptCount(task.getAttemptCount() + 1);
        eventLog.record(task, Decision.DISPATCHED,
                "消耗 " + TaskRegistrar.DEP_FUND + " 令牌 1（配额：" + quotaLabel(admission) + "；"
                        + riskEstimator.explain(task) + "），发起基金回传（第 " + task.getAttemptCount() + " 次尝试）");
        taskRepository.save(task);

        BigDecimal amount = ledgerService.byOperationNo(LedgerService.debitOpNo(task))
                .map(LedgerEntry::getAmount)
                .orElse(task.getAmount());
        LedgerEntry entry = ledgerService.postOrGetFundReturn(task, amount);
        try {
            DownstreamSimulator.FundCallResult callResult = simulator.callFundGateway(entry.getOperationNo());
            orchestrator.reportOutcome(TaskRegistrar.DEP_FUND, true);
            entry.setGatewayAccepted(true);
            ledgerService.save(entry);
            if (callResult == DownstreamSimulator.FundCallResult.RECEIPT_READY) {
                ledgerService.confirm(entry.getOperationNo());
                result.confirmed++;
            } else {
                task.setStatus(TaskStatus.PENDING_CONFIRM);
                eventLog.record(task, Decision.PENDING_CONFIRM,
                        "基金回传已受理（操作号 " + entry.getOperationNo()
                                + "），确认回执延迟：账本保持待确认，按操作号查询回执，不再申请扣款令牌");
                taskRepository.save(task);
                result.dispatched++;
            }
        } catch (TransientFailureException e) {
            orchestrator.reportOutcome(TaskRegistrar.DEP_FUND, false);
            failureClassifier.onTransient(task, e.getMessage());
            result.failed++;
        }
    }

    private static String quotaLabel(QueueQuotaController.Admission admission) {
        return switch (admission) {
            case RESERVED -> "来源保留份额（最低服务保障）";
            case BONUS -> "公共池按风险分插队（可追溯）";
            case DENIED -> "无";
        };
    }

    /** 可取的任务：READY，或退避时间已到的 RETRY_SCHEDULED；按截止风险分、金额优先级、金额、清算日、ID 排序 */
    private List<ClearingTask> eligible(Stage stage, String dependencyCode) {
        Instant now = Instant.now();
        return taskRepository
                .findByStatusInAndDependencyCode(
                        List.of(TaskStatus.READY, TaskStatus.RETRY_SCHEDULED), dependencyCode)
                .stream()
                .filter(t -> t.getStage() == stage)
                .filter(t -> t.getStatus() == TaskStatus.READY
                        || (t.getNextAttemptAt() != null && !t.getNextAttemptAt().isAfter(now)))
                .sorted(Comparator.comparing(ClearingTask::getRiskScore).reversed()
                        .thenComparing(Comparator.comparing(ClearingTask::getPriority).reversed())
                        .thenComparing(Comparator.comparing(ClearingTask::getAmount).reversed())
                        .thenComparing(ClearingTask::getClearingDate)
                        .thenComparing(ClearingTask::getId))
                .toList();
    }

    /** 同一决策不重复刷屏，只在决策变化时记录事件 */
    private void recordIfChanged(ClearingTask task, Decision decision, String reason) {
        if (task.getLastDecision() != decision) {
            eventLog.record(task, decision, reason);
            taskRepository.save(task);
        }
    }
}
