package com.example.clearing.service;

import com.example.clearing.domain.CapacityEvent;
import com.example.clearing.domain.CapacityEventType;
import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.DepHealth;
import com.example.clearing.domain.EntryStatus;
import com.example.clearing.domain.EntryType;
import com.example.clearing.domain.FailureType;
import com.example.clearing.domain.LedgerEntry;
import com.example.clearing.domain.ProfileState;
import com.example.clearing.domain.QuotaGrant;
import com.example.clearing.domain.SourceType;
import com.example.clearing.domain.Stage;
import com.example.clearing.domain.StormReport;
import com.example.clearing.domain.StormLevel;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.domain.TokenBucket;
import com.example.clearing.dto.ScenarioResult;
import com.example.clearing.repo.CapacityEventRepository;
import com.example.clearing.repo.CatalogItemRepository;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.FailureRecordRepository;
import com.example.clearing.repo.LedgerEntryRepository;
import com.example.clearing.repo.QuotaGrantRepository;
import com.example.clearing.repo.StormReportRepository;
import com.example.clearing.repo.TaskEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验收样本：医院批次、药店回传、目录冲突、接口降级与配额恢复、
 * 基金回执延迟、内容冲突、风暴观测，以及月末积压容量治理四场景
 * （局部限流 / 可追溯插队与插队保护 / 低风险最低服务 / 探测上限与保守恢复）。
 * 每个场景通过真实服务执行并输出断言。
 */
@Service
public class DemoScenarioService {

    private static final LocalDate CLEARING_DATE = LocalDate.of(2026, 11, 23);
    /** 月末积压场景统一使用的清算日（月末） */
    private static final LocalDate MONTH_END = LocalDate.of(2026, 11, 30);
    /** 目录基线（启动种子），演练复位时恢复到此集合 */
    private static final java.util.Set<String> BASELINE_CATALOG =
            java.util.Set.of("A-001", "B-002", "C-003", "D-004");

    private final TaskRegistrar registrar;
    private final ClearingScheduler scheduler;
    private final TokenBucketService bucketService;
    private final DependencyGraphService graphService;
    private final DownstreamSimulator simulator;
    private final CatalogService catalogService;
    private final LedgerService ledgerService;
    private final ManualResolutionService manualResolution;
    private final StormObserver stormObserver;
    private final CapacityProfileService profileService;
    private final DegradationOrchestrator orchestrator;
    private final ClearingTaskRepository taskRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final TaskEventRepository eventRepository;
    private final FailureRecordRepository failureRepository;
    private final StormReportRepository stormRepository;
    private final QuotaGrantRepository quotaRepository;
    private final CapacityEventRepository capacityEventRepository;
    private final CatalogItemRepository catalogItemRepository;

    public DemoScenarioService(TaskRegistrar registrar,
            ClearingScheduler scheduler,
            TokenBucketService bucketService,
            DependencyGraphService graphService,
            DownstreamSimulator simulator,
            CatalogService catalogService,
            LedgerService ledgerService,
            ManualResolutionService manualResolution,
            StormObserver stormObserver,
            CapacityProfileService profileService,
            DegradationOrchestrator orchestrator,
            ClearingTaskRepository taskRepository,
            LedgerEntryRepository ledgerRepository,
            TaskEventRepository eventRepository,
            FailureRecordRepository failureRepository,
            StormReportRepository stormRepository,
            QuotaGrantRepository quotaRepository,
            CapacityEventRepository capacityEventRepository,
            CatalogItemRepository catalogItemRepository) {
        this.registrar = registrar;
        this.scheduler = scheduler;
        this.bucketService = bucketService;
        this.graphService = graphService;
        this.simulator = simulator;
        this.catalogService = catalogService;
        this.ledgerService = ledgerService;
        this.manualResolution = manualResolution;
        this.stormObserver = stormObserver;
        this.profileService = profileService;
        this.orchestrator = orchestrator;
        this.taskRepository = taskRepository;
        this.ledgerRepository = ledgerRepository;
        this.eventRepository = eventRepository;
        this.failureRepository = failureRepository;
        this.stormRepository = stormRepository;
        this.quotaRepository = quotaRepository;
        this.capacityEventRepository = capacityEventRepository;
        this.catalogItemRepository = catalogItemRepository;
    }

    /** 演练复位：清空业务数据，恢复依赖健康、令牌桶、容量画像、目录基线与模拟器 */
    @Transactional
    public void reset() {
        eventRepository.deleteAll();
        failureRepository.deleteAll();
        stormRepository.deleteAll();
        quotaRepository.deleteAll();
        ledgerRepository.deleteAll();
        taskRepository.deleteAll();
        capacityEventRepository.deleteAll();
        // 目录恢复基线：移除演练期间同步进来的目录项（如 ZZZ-999），保证场景可重复执行
        catalogItemRepository.deleteAll(catalogItemRepository.findAll().stream()
                .filter(item -> !BASELINE_CATALOG.contains(item.getCode()))
                .toList());
        bucketService.resetAll();
        graphService.resetHealth();
        profileService.resetAll();
        orchestrator.resetSignals();
        simulator.reset();
        stormObserver.resetWindow();
    }

    @Transactional
    public ScenarioResult run(String name) {
        return switch (name) {
            case "hospital-batch" -> hospitalBatch();
            case "pharmacy-return" -> pharmacyReturn();
            case "catalog-conflict" -> catalogConflict();
            case "rule-degradation" -> ruleDegradation();
            case "fund-pending-confirm" -> fundPendingConfirm();
            case "content-conflict" -> contentConflict();
            case "storm-watch" -> stormWatch();
            case "month-end-local-throttle" -> monthEndLocalThrottle();
            case "month-end-priority-jump" -> monthEndPriorityJump();
            case "month-end-min-service" -> monthEndMinService();
            case "month-end-conservative-recovery" -> monthEndConservativeRecovery();
            default -> throw new IllegalArgumentException("未知场景: " + name
                    + "，可选：hospital-batch, pharmacy-return, catalog-conflict, rule-degradation, "
                    + "fund-pending-confirm, content-conflict, storm-watch, month-end-local-throttle, "
                    + "month-end-priority-jump, month-end-min-service, month-end-conservative-recovery");
        };
    }

    /** 场景 1：医院结算批次正常清算 */
    private ScenarioResult hospitalBatch() {
        ScenarioResult s = new ScenarioResult("hospital-batch");
        reset();
        s.step("登记 3 笔医院结算清单（金额 12000 / 45000 / 8000）");
        ClearingTask t1 = reg("HOSP-20261123-001", "v1", SourceType.HOSPITAL, "12000.00",
                payload("A-001", 2, "100.00"));
        ClearingTask t2 = reg("HOSP-20261123-002", "v1", SourceType.HOSPITAL, "45000.00",
                payload("B-002", 1, "300.00"));
        ClearingTask t3 = reg("HOSP-20261123-003", "v1", SourceType.HOSPITAL, "8000.00",
                payload("D-004", 5, "20.00"));
        s.step("执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        s.check(status(t1) == TaskStatus.CONFIRMED, "任务1 清算完成 CONFIRMED，实际=" + status(t1));
        s.check(status(t2) == TaskStatus.CONFIRMED, "任务2 清算完成 CONFIRMED，实际=" + status(t2));
        s.check(status(t3) == TaskStatus.CONFIRMED, "任务3 清算完成 CONFIRMED，实际=" + status(t3));
        s.check(taskRepository.findById(t2.getId()).orElseThrow().getPriority() == 1,
                "金额 45000 的任务被赋予 MEDIUM 金额优先级");
        long debits = ledgerRepository.findAll().stream()
                .filter(e -> e.getEntryType() == EntryType.CLEAR_DEBIT && e.getStatus() == EntryStatus.POSTED)
                .count();
        long returns = ledgerRepository.findAll().stream()
                .filter(e -> e.getEntryType() == EntryType.FUND_RETURN && e.getStatus() == EntryStatus.CONFIRMED)
                .count();
        s.check(debits == 3 && returns == 3, "幂等账本：3 笔扣款 POSTED + 3 笔回传 CONFIRMED，实际="
                + debits + "/" + returns);
        return s;
    }

    /** 场景 2：药店购药回传清算 */
    private ScenarioResult pharmacyReturn() {
        ScenarioResult s = new ScenarioResult("pharmacy-return");
        reset();
        s.step("登记 2 笔药店购药回传记录");
        ClearingTask t1 = reg("PHARM-0001", "v1", SourceType.PHARMACY, "1500.00", payload("A-001", 1, "50.00"));
        ClearingTask t2 = reg("PHARM-0002", "v1", SourceType.PHARMACY, "2300.00", payload("B-002", 3, "30.00"));
        s.step("执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        s.check(status(t1) == TaskStatus.CONFIRMED && status(t2) == TaskStatus.CONFIRMED,
                "药店回传任务全部 CONFIRMED，实际=" + status(t1) + "/" + status(t2));
        return s;
    }

    /** 场景 3：目录缺失 → 目录等待 → 目录同步后恢复 */
    private ScenarioResult catalogConflict() {
        ScenarioResult s = new ScenarioResult("catalog-conflict");
        reset();
        s.step("登记 1 笔含未知目录编码 ZZZ-999 的医院清单");
        ClearingTask task = reg("HOSP-CAT-001", "v1", SourceType.HOSPITAL, "9000.00",
                payload("ZZZ-999", 1, "100.00"));
        s.step("执行 1 个调度周期（目录匹配失败）");
        scheduler.tick();
        s.check(status(task) == TaskStatus.WAITING_DEPENDENCY, "任务进入目录等待 WAITING_DEPENDENCY，实际="
                + status(task));
        s.check(bucketService.get(TaskRegistrar.DEP_RULE).getTotalConsumed() == 0,
                "目录等待期间未消耗任何尝试令牌");
        s.check(failureRepository.findAll().stream()
                .anyMatch(f -> f.getFailureType() == FailureType.CATALOG_WAIT),
                "失败判定器记录 CATALOG_WAIT");
        s.step("目录同步：补齐目录项 ZZZ-999");
        catalogService.add("ZZZ-999", "演练新药", "2026.11");
        s.step("再执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        s.check(status(task) == TaskStatus.CONFIRMED, "目录恢复后任务自动提升并清算完成 CONFIRMED，实际="
                + status(task));
        return s;
    }

    /** 场景 4：待遇规则接口降级 → 桶冻结 → 恢复后按剩余预算渐进投放（不补发） */
    private ScenarioResult ruleDegradation() {
        ScenarioResult s = new ScenarioResult("rule-degradation");
        reset();
        s.step("登记批次 B1 共 5 笔医院清单并执行 1 个调度周期（RULE 桶容量 6，消耗 5）");
        for (int i = 1; i <= 5; i++) {
            reg("HOSP-B1-" + i, "v1", SourceType.HOSPITAL, "1000.00", payload("A-001", 1, "10.00"));
        }
        scheduler.tick();
        TokenBucket ruleBucket = bucketService.get(TaskRegistrar.DEP_RULE);
        s.check(ruleBucket.getTokens() == 1, "B1 投放后 RULE 桶余额=1，实际=" + ruleBucket.getTokens());

        s.step("待遇规则服务 DOWN（人工覆盖，接口降级），登记批次 B2 共 5 笔");
        graphService.updateHealth(TaskRegistrar.DEP_RULE, DepHealth.DOWN, "演练：规则服务不可用");
        for (int i = 1; i <= 5; i++) {
            reg("HOSP-B2-" + i, "v1", SourceType.HOSPITAL, "1000.00", payload("A-001", 1, "10.00"));
        }
        long refilledAtDown = bucketService.get(TaskRegistrar.DEP_RULE).getTotalRefilled();
        s.step("故障期间执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        TokenBucket frozen = bucketService.get(TaskRegistrar.DEP_RULE);
        s.check(frozen.getTokens() == 1 && frozen.getTotalRefilled() == refilledAtDown,
                "故障期间桶冻结：余额保持 1、零补充（不放行、不补充），实际余额=" + frozen.getTokens()
                        + " 累计补充=" + frozen.getTotalRefilled());
        boolean b2Waiting = taskRepository.findAll().stream()
                .filter(t -> t.getBusinessKey().startsWith("HOSP-B2-"))
                .allMatch(t -> t.getStatus() == TaskStatus.READY
                        && t.getLastDecision() == com.example.clearing.domain.Decision.WAIT_DEPENDENCY_DOWN);
        s.check(b2Waiting, "B2 任务保持就绪等待，决策记录为 WAIT_DEPENDENCY_DOWN");
        s.check(quotaRepository.findAll().stream()
                .anyMatch(q -> q.getDependencyCode().equals(TaskRegistrar.DEP_RULE) && q.getGranted() == 0),
                "队列配额证据：RULE_ENGINE 故障期间配额置零（故障隔离，仅收紧本依赖队列）");

        s.step("待遇规则服务恢复健康（人工覆盖），执行 1 个调度周期");
        graphService.updateHealth(TaskRegistrar.DEP_RULE, DepHealth.HEALTHY, "演练：规则服务恢复");
        scheduler.tick();
        TokenBucket afterHeal = bucketService.get(TaskRegistrar.DEP_RULE);
        s.check(afterHeal.getTotalRefilled() == refilledAtDown + 2,
                "恢复后仅按剩余预算渐进补充（+2/周期），不补发故障期间尝试：累计补充="
                        + afterHeal.getTotalRefilled() + "（故障前 " + refilledAtDown + "）");
        long b2CalcDone = taskRepository.findAll().stream()
                .filter(t -> t.getBusinessKey().startsWith("HOSP-B2-"))
                .filter(t -> t.getStage() != Stage.CALC)
                .count();
        s.check(b2CalcDone == 3, "恢复后首个周期仅投放 3 笔（冻结余额 1 + 补充 2），实际=" + b2CalcDone);

        s.step("继续调度直至全部完成");
        tickUntil(15, () -> taskRepository.findAll().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED));
        s.check(taskRepository.findAll().stream().allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED),
                "B1+B2 共 10 笔最终全部 CONFIRMED");
        return s;
    }

    /** 场景 5：基金回传已受理但回执延迟 → 账本待确认，按操作号查询，不重复扣款 */
    private ScenarioResult fundPendingConfirm() {
        ScenarioResult s = new ScenarioResult("fund-pending-confirm");
        reset();
        s.step("基金网关设为 DELAYED（受理成功，回执延迟 300s）");
        simulator.setFundMode(DownstreamSimulator.FundMode.DELAYED);
        simulator.setFundDelaySeconds(300);
        ClearingTask task = reg("HOSP-FUND-001", "v1", SourceType.HOSPITAL, "20000.00",
                payload("C-003", 1, "500.00"));
        s.step("执行 1 个调度周期（待遇计算 + 基金回传受理）");
        scheduler.tick();
        s.check(status(task) == TaskStatus.PENDING_CONFIRM, "任务进入待确认 PENDING_CONFIRM，实际=" + status(task));
        String opNo = LedgerService.fundReturnOpNo(taskRepository.findById(task.getId()).orElseThrow());
        LedgerEntry entry = ledgerService.byOperationNo(opNo).orElseThrow();
        s.check(entry.getStatus() == EntryStatus.PENDING_CONFIRM && entry.isGatewayAccepted(),
                "回传分录 " + opNo + " 保持待确认且网关已受理");
        long consumedAfterAccept = bucketService.get(TaskRegistrar.DEP_FUND).getTotalConsumed();

        s.step("回执延迟期间再执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        s.check(bucketService.get(TaskRegistrar.DEP_FUND).getTotalConsumed() == consumedAfterAccept,
                "待确认期间仅按操作号查询回执，未取得新的扣款令牌（FUND 桶消耗保持 "
                        + consumedAfterAccept + "）");
        long entries = ledgerRepository.findAll().stream()
                .filter(e -> e.getTaskId().equals(task.getId())).count();
        s.check(entries == 2, "幂等账本对该清单仅 2 条分录（1 扣款 + 1 回传），无重复记账，实际=" + entries);
        s.check(status(task) == TaskStatus.PENDING_CONFIRM, "回执未到，任务保持待确认");

        s.step("回执到达（人工补确认），再执行 1 个调度周期");
        simulator.confirmManually(opNo);
        scheduler.tick();
        s.check(status(task) == TaskStatus.CONFIRMED, "回执确认后任务完成 CONFIRMED，实际=" + status(task));
        s.check(ledgerService.byOperationNo(opNo).orElseThrow().getStatus() == EntryStatus.CONFIRMED,
                "回传分录按操作号确认 CONFIRMED");
        return s;
    }

    /** 场景 6：同业务键不同内容重复到达 → 内容冲突，不发放令牌 */
    private ScenarioResult contentConflict() {
        ScenarioResult s = new ScenarioResult("content-conflict");
        reset();
        s.step("登记清单 KEY-CC-001 v1（内容 P1）");
        TaskRegistrar.RegisterResult first = registrar.register("BATCH-CC", CLEARING_DATE, "KEY-CC-001",
                "v1", SourceType.HOSPITAL, new BigDecimal("5000.00"), payload("A-001", 1, "100.00"));
        s.check(first.outcome() == TaskRegistrar.Outcome.CREATED, "首次登记成功 CREATED");
        s.step("同业务键同版本但内容不同（P2）重复到达");
        TaskRegistrar.RegisterResult second = registrar.register("BATCH-CC2", CLEARING_DATE, "KEY-CC-001",
                "v1", SourceType.HOSPITAL, new BigDecimal("5000.00"), payload("A-001", 2, "100.00"));
        s.check(second.outcome() == TaskRegistrar.Outcome.CONTENT_CONFLICT,
                "登记器生成内容冲突 CONTENT_CONFLICT，实际=" + second.outcome());
        s.check(bucketService.get(TaskRegistrar.DEP_RULE).getTotalConsumed() == 0,
                "内容冲突未发放任何尝试令牌");
        s.check(failureRepository.findAll().stream()
                .anyMatch(f -> f.getFailureType() == FailureType.CONTENT_MISMATCH),
                "失败判定器记录 CONTENT_MISMATCH");
        s.step("人工裁定：保留原清单，冲突登记作废");
        manualResolution.resolve(second.task().getId(), ManualResolutionService.Action.KEEP_ORIGINAL, "验收裁定");
        s.check(status(second.task()) == TaskStatus.CANCELLED, "冲突登记已作废 CANCELLED");
        s.step("执行调度，原清单正常清算");
        scheduler.tick();
        scheduler.tick();
        s.check(status(first.task()) == TaskStatus.CONFIRMED, "原清单清算完成 CONFIRMED，实际=" + status(first.task()));
        s.check(ledgerRepository.findByBusinessKey("KEY-CC-001").size() == 2,
                "账本仅含原清单的 2 条分录（冲突内容未入账）");
        return s;
    }

    /** 场景 7：规则接口连续超时 → 失败聚集触发风暴观测 */
    private ScenarioResult stormWatch() {
        ScenarioResult s = new ScenarioResult("storm-watch");
        reset();
        s.step("待遇规则服务设为 TIMEOUT（调用即超时），登记 6 笔任务");
        simulator.setRuleMode(DownstreamSimulator.RuleMode.TIMEOUT);
        for (int i = 1; i <= 6; i++) {
            reg("HOSP-S-" + i, "v1", SourceType.HOSPITAL, "2000.00", payload("A-001", 1, "20.00"));
        }
        s.step("执行 1 个调度周期（6 次尝试全部瞬时失败）");
        scheduler.tick();
        StormReport latest = stormObserver.latest().stream()
                .filter(r -> r.getDependencyCode().equals(TaskRegistrar.DEP_RULE))
                .findFirst().orElseThrow();
        s.check(latest.getStormLevel() == StormLevel.STORM,
                "风暴观测器判定 RULE_ENGINE 失败聚集为 STORM，实际=" + latest.getStormLevel()
                        + "（窗口失败 " + latest.getFailuresWindow() + " 次）");
        boolean allRetry = taskRepository.findAll().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.RETRY_SCHEDULED);
        s.check(allRetry, "全部任务进入退避重试 RETRY_SCHEDULED");
        s.step("接口恢复，等待退避窗口（1.2s）后继续调度直至完成");
        simulator.setRuleMode(DownstreamSimulator.RuleMode.NORMAL);
        sleep(1200);
        tickUntil(15, () -> taskRepository.findAll().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED));
        s.check(taskRepository.findAll().stream().allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED),
                "风暴平息后全部任务最终 CONFIRMED");
        s.check(stormRepository.count() > 0, "风暴报告已留存供复盘");
        return s;
    }

    /**
     * 场景 8（月末积压）：基金接口错误而数据库健康 → 局部限流。
     * 基金网关故障被降级编排器逐级收紧（DEGRADED→DOWN 熔断），
     * 期间清算数据库与待遇规则服务照常推进新任务，故障只收紧基金自身队列。
     */
    private ScenarioResult monthEndLocalThrottle() {
        ScenarioResult s = new ScenarioResult("month-end-local-throttle");
        reset();
        s.step("月末积压：基金网关开始报错（模拟器 DOWN），登记 6 笔月末医院清单（清算日 " + MONTH_END + "）");
        simulator.setFundMode(DownstreamSimulator.FundMode.DOWN);
        for (int i = 1; i <= 6; i++) {
            regMonthEnd("ME-LT-" + i, SourceType.HOSPITAL, "30000.00", null);
        }
        s.step("周期1：6 笔完成待遇计算（RULE 6→0、清算库 12→6），3 笔基金回传尝试失败");
        scheduler.tick();
        s.step("周期2-3：基金回传继续失败（每周期 1 次），降级编排器记录连续失败周期");
        scheduler.tick();
        scheduler.tick();
        s.step("周期4：降级编排器将 FUND_GW 熔断（生效上限 0、冻结桶），其余依赖不受影响");
        scheduler.tick();
        CapacityProfile fundProfile = profileService.get(TaskRegistrar.DEP_FUND);
        s.check(fundProfile.getState() == ProfileState.DOWN && fundProfile.getEffectiveCeiling() == 0,
                "局部限流：FUND_GW 画像 DOWN、生效上限 0，实际=" + fundProfile.getState() + "/"
                        + fundProfile.getEffectiveCeiling());
        s.check(bucketService.get(TaskRegistrar.DEP_FUND).isFrozen(), "FUND_GW 本地令牌桶已冻结");
        CapacityProfile dbProfile = profileService.get(DependencyHealthProbe.DEP_DB);
        CapacityProfile ruleProfile = profileService.get(TaskRegistrar.DEP_RULE);
        s.check(dbProfile.getState() == ProfileState.NORMAL && ruleProfile.getState() == ProfileState.NORMAL,
                "数据库与待遇规则画像保持 NORMAL（故障不扩散），实际=" + dbProfile.getState() + "/"
                        + ruleProfile.getState());
        boolean noClampOnOthers = capacityEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == CapacityEventType.ENTER_DOWN
                        || e.getEventType() == CapacityEventType.ENTER_DEGRADED)
                .allMatch(e -> e.getDependencyCode().equals(TaskRegistrar.DEP_FUND));
        s.check(noClampOnOthers, "容量事件审计：降级/熔断事件只作用于 FUND_GW，未波及其他依赖");
        long fundConsumedAtDown = bucketService.get(TaskRegistrar.DEP_FUND).getTotalConsumed();
        long ruleConsumedAtDown = bucketService.get(TaskRegistrar.DEP_RULE).getTotalConsumed();
        long dbConsumedAtDown = bucketService.get(DependencyHealthProbe.DEP_DB).getTotalConsumed();

        s.step("基金故障期间：再登记 3 笔月末清单并执行 1 个调度周期");
        for (int i = 7; i <= 9; i++) {
            regMonthEnd("ME-LT-" + i, SourceType.HOSPITAL, "30000.00", null);
        }
        scheduler.tick();
        long ruleDelta = bucketService.get(TaskRegistrar.DEP_RULE).getTotalConsumed() - ruleConsumedAtDown;
        long dbDelta = bucketService.get(DependencyHealthProbe.DEP_DB).getTotalConsumed() - dbConsumedAtDown;
        s.check(ruleDelta == 3 && dbDelta == 3,
                "基金熔断期间数据库与规则服务继续推进：RULE 消耗 +" + ruleDelta + "、清算库消耗 +" + dbDelta
                        + "（各应为 +3）");
        s.check(bucketService.get(TaskRegistrar.DEP_FUND).getTotalConsumed() == fundConsumedAtDown,
                "基金熔断期间 FUND_GW 零投放（令牌消耗保持 " + fundConsumedAtDown + "），基金接口得到保护");
        long newCalcDone = taskRepository.findAll().stream()
                .filter(t -> t.getBusinessKey().matches("ME-LT-[789]"))
                .filter(t -> t.getStage() == Stage.RETURN)
                .count();
        s.check(newCalcDone == 3, "新登记 3 笔在基金故障期间完成待遇计算进入回传阶段，实际=" + newCalcDone);
        s.check(capacityEventRepository.findAll().stream()
                .anyMatch(e -> e.getDependencyCode().equals(TaskRegistrar.DEP_FUND)
                        && e.getEventType() == CapacityEventType.ENTER_DOWN),
                "容量事件审计记录 FUND_GW 熔断（ENTER_DOWN）");
        return s;
    }

    /**
     * 场景 9（月末积压）：高金额临期任务到达 → 可追溯插队 + 插队保护。
     * 高金额临期任务按截止风险分排前并消耗公共池配额（证据可查），
     * 但低风险来源的每来源保留份额不被侵占（最低服务保障）。
     */
    private ScenarioResult monthEndPriorityJump() {
        ScenarioResult s = new ScenarioResult("month-end-priority-jump");
        reset();
        s.step("月末积压：登记 6 笔医院清单排空 RULE 桶（容量 6），执行 1 个周期");
        for (int i = 1; i <= 6; i++) {
            regMonthEnd("ME-PJ-W" + i, SourceType.HOSPITAL, "20000.00", null);
        }
        scheduler.tick();
        s.step("登记 2 笔低风险药店清单（金额 500），执行 1 个周期（RULE 补充 2，全部消耗）");
        regMonthEnd("ME-PJ-P1", SourceType.PHARMACY, "500.00", null);
        regMonthEnd("ME-PJ-P2", SourceType.PHARMACY, "500.00", null);
        scheduler.tick();
        s.check(bucketService.get(TaskRegistrar.DEP_RULE).getTokens() == 0,
                "RULE 桶已排空，实际余额=" + bucketService.get(TaskRegistrar.DEP_RULE).getTokens());
        s.step("再执行 1 个空周期让桶补充到 2（无新登记）");
        scheduler.tick();
        s.step("低风险药店队列积压 3 笔；随后 3 笔高金额临期医院任务到达（金额 90000，截止 30 分钟后）");
        regMonthEnd("ME-PJ-Q1", SourceType.PHARMACY, "500.00", null);
        regMonthEnd("ME-PJ-Q2", SourceType.PHARMACY, "500.00", null);
        regMonthEnd("ME-PJ-Q3", SourceType.PHARMACY, "500.00", null);
        Instant urgent = Instant.now().plus(30, ChronoUnit.MINUTES);
        regMonthEnd("ME-PJ-U1", SourceType.HOSPITAL, "90000.00", urgent);
        regMonthEnd("ME-PJ-U2", SourceType.HOSPITAL, "90000.00", urgent);
        regMonthEnd("ME-PJ-U3", SourceType.HOSPITAL, "90000.00", urgent);
        ClearingTask urgent1 = taskOf("ME-PJ-U1");
        s.check(urgent1.getRiskScore() == 9.0 && "CRITICAL".equals(urgent1.getRiskBand()),
                "截止风险估计：高金额临期任务风险分 9.0（金额HIGH×临期×3）、风险带 CRITICAL，实际="
                        + urgent1.getRiskScore() + "/" + urgent1.getRiskBand());

        s.step("执行 1 个调度周期（RULE 补充 2 → 可用槽位 4：医院保留 1 + 药店保留 1 + 公共池 2）");
        scheduler.tick();
        long urgentCalcDone = countByPrefixCalcDone("ME-PJ-U");
        long pharmacyCalcDone = countByPrefixCalcDone("ME-PJ-Q");
        s.check(urgentCalcDone == 3, "3 笔高金额临期任务全部于本周期完成待遇计算（可追溯插队），实际=" + urgentCalcDone);
        s.check(pharmacyCalcDone == 1, "低风险药店来源本周期仍推进 1 笔（保留份额被保护），实际=" + pharmacyCalcDone);
        List<QuotaGrant> grants = quotaRepository.findAll().stream()
                .filter(q -> q.getDependencyCode().equals(TaskRegistrar.DEP_RULE))
                .filter(q -> q.getPeriodLabel().equals(latestPeriodLabel()))
                .toList();
        QuotaGrant hospGrant = grants.stream()
                .filter(q -> q.getSourceType().equals("HOSPITAL")).findFirst().orElseThrow();
        QuotaGrant pharmGrant = grants.stream()
                .filter(q -> q.getSourceType().equals("PHARMACY")).findFirst().orElseThrow();
        s.check(hospGrant.getReservedGranted() == 1 && hospGrant.getBonusGranted() == 2
                && hospGrant.getMaxRiskScore() == 9.0,
                "配额证据：医院来源保留授予 1 + 公共池授予 2（最高风险分 9.0），实际="
                        + hospGrant.getReservedGranted() + "+" + hospGrant.getBonusGranted() + "/"
                        + hospGrant.getMaxRiskScore());
        s.check(pharmGrant.getReservedGranted() == 1 && pharmGrant.getBonusGranted() == 0,
                "插队保护：药店来源保留授予 1 未被医院侵占（公共池 0），实际="
                        + pharmGrant.getReservedGranted() + "+" + pharmGrant.getBonusGranted());
        boolean urgentEventTraceable = eventRepository.findByTaskIdOrderByCreatedAtAsc(urgent1.getId()).stream()
                .anyMatch(e -> e.getReason() != null && e.getReason().contains("风险分 9.0"));
        s.check(urgentEventTraceable, "高金额临期任务的投放事件记录风险分与配额来源（优先级可追溯）");
        boolean pharmacyThrottled = taskRepository.findAll().stream()
                .filter(t -> t.getBusinessKey().matches("ME-PJ-Q[23]"))
                .anyMatch(t -> t.getLastDecision() == com.example.clearing.domain.Decision.THROTTLED);
        s.check(pharmacyThrottled, "未获配额的药店任务记录 THROTTLED（配额耗尽等待下周期），决策可复核");
        s.step("继续调度直至全部完成");
        tickUntil(20, () -> taskRepository.findAll().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED));
        s.check(taskRepository.findAll().stream().allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED),
                "全部任务最终 CONFIRMED");
        return s;
    }

    /**
     * 场景 10（月末积压）：低风险来源队列长期积压 → 每来源最低服务份额。
     * 高金额临期任务持续占满公共池期间，低风险药店来源每个周期至少推进 1 笔，
     * 永不挂零（不被永久饿死），配额证据逐周期可查。
     */
    private ScenarioResult monthEndMinService() {
        ScenarioResult s = new ScenarioResult("month-end-min-service");
        reset();
        s.step("月末积压：登记 6 笔医院清单排空 RULE 桶，执行 1 个周期");
        for (int i = 1; i <= 6; i++) {
            regMonthEnd("ME-MS-W" + i, SourceType.HOSPITAL, "20000.00", null);
        }
        scheduler.tick();
        s.step("低风险药店来源积压 5 笔（金额 300），同时 5 笔高金额临期医院任务（90000，截止 30 分钟后）持续竞争");
        for (int i = 1; i <= 5; i++) {
            regMonthEnd("ME-MS-P" + i, SourceType.PHARMACY, "300.00", null);
        }
        Instant urgent = Instant.now().plus(30, ChronoUnit.MINUTES);
        for (int i = 1; i <= 5; i++) {
            regMonthEnd("ME-MS-H" + i, SourceType.HOSPITAL, "90000.00", urgent);
        }
        boolean minServiceEveryTick = true;
        StringBuilder progress = new StringBuilder();
        for (int tick = 1; tick <= 5; tick++) {
            long before = countByPrefixCalcDone("ME-MS-P");
            scheduler.tick();
            long after = countByPrefixCalcDone("ME-MS-P");
            progress.append("周期").append(tick).append("+").append(after - before).append(" ");
            if (after - before < 1) {
                minServiceEveryTick = false;
            }
        }
        s.check(minServiceEveryTick,
                "最低服务：高风险竞争下低风险药店来源连续 5 个周期每周期至少推进 1 笔（" + progress + "）");
        s.check(countByPrefixCalcDone("ME-MS-P") == 5,
                "5 笔低风险任务按最低服务份额全部完成待遇计算，实际=" + countByPrefixCalcDone("ME-MS-P"));
        long pharmacyReservedGrants = quotaRepository.findAll().stream()
                .filter(q -> q.getDependencyCode().equals(TaskRegistrar.DEP_RULE))
                .filter(q -> q.getSourceType().equals("PHARMACY"))
                .mapToInt(QuotaGrant::getReservedGranted)
                .sum();
        s.check(pharmacyReservedGrants >= 5,
                "配额证据：药店来源保留份额授予累计 " + pharmacyReservedGrants
                        + "（≥5，每周期最低服务份额可追溯）");
        s.step("继续调度直至全部完成");
        tickUntil(25, () -> taskRepository.findAll().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED));
        s.check(taskRepository.findAll().stream().allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED),
                "全部任务最终 CONFIRMED（低风险来源未被永久饿死）");
        return s;
    }

    /**
     * 场景 11（月末积压）：健康信号短暂恢复 → 探测上限 + 保守恢复。
     * 单次恢复信号不被追认（需连续确认）；探测严格在预算上限内；
     * 确认恢复后从下限保守爬坡，不一步恢复全量。
     */
    private ScenarioResult monthEndConservativeRecovery() {
        ScenarioResult s = new ScenarioResult("month-end-conservative-recovery");
        reset();
        s.step("待遇规则服务持续超时（模拟器 TIMEOUT），登记 3 笔月末医院清单");
        simulator.setRuleMode(DownstreamSimulator.RuleMode.TIMEOUT);
        for (int i = 1; i <= 3; i++) {
            regMonthEnd("ME-CR-" + i, SourceType.HOSPITAL, "20000.00", null);
        }
        s.step("周期1：3 次尝试全部瞬时失败（退避 1s）");
        scheduler.tick();
        s.step("等待退避（1.1s）后周期2-3：继续失败，降级编排器将 RULE_ENGINE 降为 DEGRADED");
        sleep(1100);
        scheduler.tick();
        scheduler.tick();
        s.check(profileService.get(TaskRegistrar.DEP_RULE).getState() == ProfileState.DEGRADED,
                "连续失败 2 个周期后画像降级 DEGRADED，实际=" + profileService.get(TaskRegistrar.DEP_RULE).getState());
        s.step("等待退避（2.1s）后周期4：再次失败，编排器熔断为 DOWN（生效上限 0、冻结桶）");
        sleep(2100);
        scheduler.tick();
        scheduler.tick();
        s.check(profileService.get(TaskRegistrar.DEP_RULE).getState() == ProfileState.DOWN,
                "连续失败 3 个周期后画像熔断 DOWN，实际=" + profileService.get(TaskRegistrar.DEP_RULE).getState());
        s.check(bucketService.get(TaskRegistrar.DEP_RULE).isFrozen(), "RULE 桶已冻结");

        s.step("故障期第 1 个探测周期：探测失败（模拟器仍 TIMEOUT）");
        scheduler.tick();
        s.step("健康信号短暂恢复：接口仅恢复 1 个探测周期后再次超时");
        simulator.setRuleMode(DownstreamSimulator.RuleMode.NORMAL);
        scheduler.tick();
        simulator.setRuleMode(DownstreamSimulator.RuleMode.TIMEOUT);
        scheduler.tick();
        CapacityProfile afterBlip = profileService.get(TaskRegistrar.DEP_RULE);
        s.check(afterBlip.getState() == ProfileState.DOWN && afterBlip.getEffectiveCeiling() == 0,
                "保守恢复：单次恢复信号不被追认，画像保持 DOWN、生效上限 0，实际=" + afterBlip.getState());
        s.check(bucketService.get(TaskRegistrar.DEP_RULE).isFrozen()
                && bucketService.get(TaskRegistrar.DEP_RULE).getRefillPerTick() == 0,
                "短暂恢复期间桶保持冻结、零补充（未放开）");
        s.check(afterBlip.getTotalProbes() == 3,
                "探测上限：3 个故障周期每周期恰好 1 次探测（上限 1/依赖/周期），实际=" + afterBlip.getTotalProbes());
        long probeEvents = capacityEventRepository.findAll().stream()
                .filter(e -> e.getDependencyCode().equals(TaskRegistrar.DEP_RULE))
                .filter(e -> e.getEventType() == CapacityEventType.PROBE_OK
                        || e.getEventType() == CapacityEventType.PROBE_FAIL)
                .count();
        s.check(probeEvents == 3, "探测审计：3 条探测事件（2 失败 + 1 成功），实际=" + probeEvents);
        s.check(capacityEventRepository.findAll().stream()
                .noneMatch(e -> e.getEventType() == CapacityEventType.RECOVERY_STARTED),
                "短暂恢复未触发任何恢复爬坡（无 RECOVERY_STARTED 事件）");

        s.step("接口持续恢复健康：连续探测确认后进入保守爬坡");
        simulator.setRuleMode(DownstreamSimulator.RuleMode.NORMAL);
        scheduler.tick();
        scheduler.tick();
        CapacityProfile recovering = profileService.get(TaskRegistrar.DEP_RULE);
        s.check(recovering.getState() == ProfileState.RECOVERING && recovering.getEffectiveCeiling() == 1,
                "连续 2 次探测成功进入恢复爬坡：生效上限从 1/周期起步（声明上限 2），实际="
                        + recovering.getState() + "/" + recovering.getEffectiveCeiling());
        s.check(bucketService.get(TaskRegistrar.DEP_RULE).getRefillPerTick() == 1,
                "爬坡期间桶补充速率 1/周期（不一步恢复全量），实际="
                        + bucketService.get(TaskRegistrar.DEP_RULE).getRefillPerTick());
        s.step("再稳定 1 个周期：爬坡到达声明上限，恢复 NORMAL");
        scheduler.tick();
        CapacityProfile recovered = profileService.get(TaskRegistrar.DEP_RULE);
        s.check(recovered.getState() == ProfileState.NORMAL
                && recovered.getEffectiveCeiling() == recovered.getDeclaredCeiling(),
                "爬坡到达声明上限后恢复 NORMAL（全程未越过声明上限），实际=" + recovered.getState() + "/"
                        + recovered.getEffectiveCeiling());
        s.check(capacityEventRepository.findAll().stream()
                .anyMatch(e -> e.getEventType() == CapacityEventType.RECOVERED),
                "容量事件审计记录完整恢复轨迹（RECOVERED）");
        s.step("等待退避窗口（4.5s）后继续调度直至全部完成");
        sleep(4500);
        tickUntil(15, () -> taskRepository.findAll().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED));
        s.check(taskRepository.findAll().stream().allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED),
                "恢复后全部任务最终 CONFIRMED");
        return s;
    }

    // ---------- 工具 ----------

    private ClearingTask reg(String key, String version, SourceType source, String amount, String payload) {
        return registrar.register("BATCH-" + key, CLEARING_DATE, key, version, source,
                new BigDecimal(amount), payload).task();
    }

    /** 月末场景登记：清算日为月末，可显式指定截止时间 */
    private ClearingTask regMonthEnd(String key, SourceType source, String amount, Instant deadlineAt) {
        return registrar.register("BATCH-" + key, MONTH_END, key, "v1", source,
                new BigDecimal(amount), payload("A-001", 1, "10.00"), deadlineAt).task();
    }

    private ClearingTask taskOf(String businessKey) {
        return taskRepository.findAll().stream()
                .filter(t -> t.getBusinessKey().equals(businessKey))
                .findFirst().orElseThrow();
    }

    /** 已完成待遇计算（阶段已离开 CALC：RETURN 或 DONE）的任务数 */
    private long countByPrefixCalcDone(String keyPrefix) {
        return taskRepository.findAll().stream()
                .filter(t -> t.getBusinessKey().startsWith(keyPrefix))
                .filter(t -> t.getStage() != Stage.CALC)
                .count();
    }

    private String latestPeriodLabel() {
        return quotaRepository.findAll().stream()
                .map(QuotaGrant::getPeriodLabel)
                .max(String::compareTo)
                .orElse("");
    }

    private static String payload(String code, int qty, String price) {
        return "{\"items\":[{\"catalogCode\":\"" + code + "\",\"qty\":" + qty + ",\"price\":" + price + "}]}";
    }

    private TaskStatus status(ClearingTask task) {
        return taskRepository.findById(task.getId()).orElseThrow().getStatus();
    }

    private void tickUntil(int maxTicks, BooleanSupplier done) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            scheduler.tick();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
