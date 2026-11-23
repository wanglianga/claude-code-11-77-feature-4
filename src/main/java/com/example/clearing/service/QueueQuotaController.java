package com.example.clearing.service;

import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.ProfileState;
import com.example.clearing.domain.QuotaGrant;
import com.example.clearing.domain.SourceType;
import com.example.clearing.repo.QuotaGrantRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 队列配额控制器：每个调度周期把依赖的可用投放槽位分配给排队来源。
 * 路线 A 的两条硬规则：
 *  1. 每来源最低服务份额：每个有等待任务的来源先保留 minSharePerSource 个槽位，
 *     该保留份额永远不被其他来源侵占（低风险来源不会被高优先级永久饿死）；
 *  2. 剩余槽位进入公共池，由调度器按截止风险分从高到低消费——高金额临期任务
 *     的"插队"只发生在公共池内，且每次授予都写入配额证据（可追溯）。
 * 槽位总数永远不超过容量画像的生效上限（≤ 声明上限），不做突破依赖容量的统一扩容。
 */
@Service
public class QueueQuotaController {

    /** 准入结果：保留份额内 / 公共池插队 / 拒绝（本周期配额耗尽） */
    public enum Admission {
        RESERVED,
        BONUS,
        DENIED
    }

    private final CapacityProfileService profileService;
    private final QuotaGrantRepository quotaRepository;

    /** 本周期各依赖的配额计划（调度周期开始重建） */
    private final Map<String, QuotaPlan> plans = new ConcurrentHashMap<>();
    /** 周期序号：配额证据的 periodLabel 按序号单调递增，同秒多周期也不撞标签 */
    private final java.util.concurrent.atomic.AtomicLong tickSequence = new java.util.concurrent.atomic.AtomicLong();

    public QueueQuotaController(CapacityProfileService profileService, QuotaGrantRepository quotaRepository) {
        this.profileService = profileService;
        this.quotaRepository = quotaRepository;
    }

    /** 调度周期开始：清空上一周期计划并推进周期序号 */
    public void beginTick() {
        plans.clear();
        tickSequence.incrementAndGet();
    }

    /**
     * 为某依赖制定本周期配额计划。
     * @param availableSlots 本周期可用投放槽位（取自已补充的本地桶；依赖 DOWN/冻结时为 0）
     * @param eligible 本周期该依赖可取的任务（调度器已按风险分排序）
     */
    public void planFor(String dependencyCode, int availableSlots, List<ClearingTask> eligible) {
        CapacityProfile profile = profileService.get(dependencyCode);
        int minShare = profile != null ? profile.getMinSharePerSource() : 1;
        boolean depDown = profile != null && profile.getState() == ProfileState.DOWN;
        int slots = depDown ? 0 : Math.max(0, availableSlots);

        QuotaPlan plan = new QuotaPlan(dependencyCode, slots, minShare, depDown);
        // 需求分组：来源 × 金额优先级带（证据粒度）
        for (ClearingTask task : eligible) {
            plan.demand(task);
        }
        // 每来源保留最低服务份额
        Map<SourceType, Integer> demandBySource = new TreeMap<>();
        for (ClearingTask task : eligible) {
            demandBySource.merge(task.getSourceType(), 1, Integer::sum);
        }
        int reservedTotal = 0;
        for (Map.Entry<SourceType, Integer> e : demandBySource.entrySet()) {
            int reserved = Math.min(minShare, e.getValue());
            plan.reservedRemaining.put(e.getKey(), reserved);
            reservedTotal += reserved;
        }
        // 退化情形：槽位少于保留总额——按"等待最久的来源优先"逐轮分配，确定且可审计
        if (reservedTotal > slots) {
            allocateReservedBySeniority(plan, eligible, slots);
            reservedTotal = slots;
        }
        plan.bonusRemaining = slots - reservedTotal;
        plans.put(dependencyCode, plan);
    }

    /** 槽位不足覆盖全部保留份额时，按来源内最久等待任务的时间逐轮分配 */
    private void allocateReservedBySeniority(QuotaPlan plan, List<ClearingTask> eligible, int slots) {
        List<SourceType> bySeniority = new ArrayList<>();
        eligible.stream()
                .sorted(Comparator.comparing(ClearingTask::getCreatedAt).thenComparing(ClearingTask::getId))
                .map(ClearingTask::getSourceType)
                .distinct()
                .forEach(bySeniority::add);
        plan.reservedRemaining.replaceAll((s, v) -> 0);
        int left = slots;
        while (left > 0) {
            boolean progressed = false;
            for (SourceType source : bySeniority) {
                int current = plan.reservedRemaining.getOrDefault(source, 0);
                int demand = plan.demandOfSource(source);
                if (left > 0 && current < Math.min(plan.minShare, demand)) {
                    plan.reservedRemaining.put(source, current + 1);
                    left--;
                    progressed = true;
                }
            }
            if (!progressed) {
                break;
            }
        }
    }

    /** 尝试为准入任务消费一个配额槽：先耗本来源保留份额，再耗公共池 */
    public Admission admit(String dependencyCode, ClearingTask task) {
        QuotaPlan plan = plans.get(dependencyCode);
        if (plan == null) {
            return Admission.DENIED;
        }
        int reservedLeft = plan.reservedRemaining.getOrDefault(task.getSourceType(), 0);
        if (reservedLeft > 0) {
            plan.reservedRemaining.put(task.getSourceType(), reservedLeft - 1);
            plan.grant(task, true);
            return Admission.RESERVED;
        }
        if (plan.bonusRemaining > 0) {
            plan.bonusRemaining--;
            plan.grant(task, false);
            return Admission.BONUS;
        }
        return Admission.DENIED;
    }

    /** 周期结束：把各依赖的配额授予写入配额证据（quota_grant），供审计复核 */
    @Transactional(propagation = Propagation.REQUIRED)
    public List<QuotaGrant> flushGrants() {
        List<QuotaGrant> written = new ArrayList<>();
        String period = String.format("QT-%06d", tickSequence.get());
        for (QuotaPlan plan : plans.values()) {
            for (DemandStat stat : plan.stats.values()) {
                if (stat.demand == 0) {
                    continue;
                }
                QuotaGrant grant = new QuotaGrant();
                grant.setDependencyCode(plan.dependencyCode);
                grant.setSourceType(stat.source.name());
                grant.setPriorityBand(stat.band);
                grant.setDemand(stat.demand);
                grant.setReservedGranted(stat.reservedGranted);
                grant.setBonusGranted(stat.bonusGranted);
                grant.setGranted(stat.reservedGranted + stat.bonusGranted);
                grant.setMaxRiskScore(stat.maxRisk);
                grant.setPeriodLabel(period);
                grant.setReason(reasonOf(plan, stat));
                quotaRepository.save(grant);
                written.add(grant);
            }
        }
        return written;
    }

    private String reasonOf(QuotaPlan plan, DemandStat stat) {
        if (plan.depDown) {
            return "依赖不可用：生效上限 0，保留份额与公共池均冻结（局部限流仅收紧本依赖，不影响其他依赖）";
        }
        return "本周期可用槽位 " + plan.slots + "（不超过容量画像生效上限，声明上限为硬边界）；该组需求 "
                + stat.demand + "；每来源最低服务份额 " + plan.minShare + "（保留授予 " + stat.reservedGranted
                + "，其他来源不可侵占）；公共池按截止风险分授予 " + stat.bonusGranted
                + "（可追溯插队，组内最高风险分 " + stat.maxRisk + "）";
    }

    /** 本周期某依赖的计划快照（测试与诊断用） */
    public QuotaPlan planOf(String dependencyCode) {
        return plans.get(dependencyCode);
    }

    /** 单个依赖的周期配额计划 */
    public static final class QuotaPlan {
        private final String dependencyCode;
        private final int slots;
        private final int minShare;
        private final boolean depDown;
        private final Map<SourceType, Integer> reservedRemaining = new TreeMap<>();
        private int bonusRemaining;
        private final Map<String, DemandStat> stats = new LinkedHashMap<>();

        QuotaPlan(String dependencyCode, int slots, int minShare, boolean depDown) {
            this.dependencyCode = dependencyCode;
            this.slots = slots;
            this.minShare = minShare;
            this.depDown = depDown;
        }

        void demand(ClearingTask task) {
            DemandStat stat = stats.computeIfAbsent(keyOf(task),
                    k -> new DemandStat(task.getSourceType(), TaskRegistrar.bandOf(task.getPriority())));
            stat.demand++;
            stat.maxRisk = Math.max(stat.maxRisk, task.getRiskScore());
        }

        void grant(ClearingTask task, boolean reserved) {
            DemandStat stat = stats.get(keyOf(task));
            if (reserved) {
                stat.reservedGranted++;
            } else {
                stat.bonusGranted++;
            }
        }

        int demandOfSource(SourceType source) {
            return stats.values().stream()
                    .filter(s -> s.source == source)
                    .mapToInt(s -> s.demand)
                    .sum();
        }

        private static String keyOf(ClearingTask task) {
            return task.getSourceType() + "|" + TaskRegistrar.bandOf(task.getPriority());
        }

        public int getSlots() {
            return slots;
        }

        public int getBonusRemaining() {
            return bonusRemaining;
        }

        public Map<SourceType, Integer> getReservedRemaining() {
            return reservedRemaining;
        }
    }

    /** 来源 × 金额带的需求与授予统计 */
    private static final class DemandStat {
        private final SourceType source;
        private final String band;
        private int demand;
        private int reservedGranted;
        private int bonusGranted;
        private double maxRisk;

        DemandStat(SourceType source, String band) {
            this.source = source;
            this.band = band;
        }
    }
}
