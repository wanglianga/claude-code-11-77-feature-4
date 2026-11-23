package com.example.clearing.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clearing")
public class ClearingProperties {

    /** 待遇规则版本（登记时写入任务依赖版本快照） */
    private String ruleVersion = "2026.11";

    private Scheduler scheduler = new Scheduler();
    private Backoff backoff = new Backoff();
    private Storm storm = new Storm();
    private Coordinator coordinator = new Coordinator();
    private Capacity capacity = new Capacity();

    /** 依赖本地令牌桶配置：dependencyCode -> 桶规格 */
    private Map<String, BucketSpec> buckets = new LinkedHashMap<>();

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public Backoff getBackoff() {
        return backoff;
    }

    public void setBackoff(Backoff backoff) {
        this.backoff = backoff;
    }

    public Storm getStorm() {
        return storm;
    }

    public void setStorm(Storm storm) {
        this.storm = storm;
    }

    public Coordinator getCoordinator() {
        return coordinator;
    }

    public void setCoordinator(Coordinator coordinator) {
        this.coordinator = coordinator;
    }

    public Capacity getCapacity() {
        return capacity;
    }

    public void setCapacity(Capacity capacity) {
        this.capacity = capacity;
    }

    public Map<String, BucketSpec> getBuckets() {
        return buckets;
    }

    public void setBuckets(Map<String, BucketSpec> buckets) {
        this.buckets = buckets;
    }

    public static class Scheduler {
        /** 是否开启自动调度（默认关闭，验收通过手动 tick 驱动，保证确定性） */
        private boolean autoEnabled = false;
        private long intervalMs = 3000;

        public boolean isAutoEnabled() {
            return autoEnabled;
        }

        public void setAutoEnabled(boolean autoEnabled) {
            this.autoEnabled = autoEnabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    public static class Backoff {
        private long baseMs = 1000;
        private long maxMs = 10000;
        private int maxAttempts = 5;

        public long getBaseMs() {
            return baseMs;
        }

        public void setBaseMs(long baseMs) {
            this.baseMs = baseMs;
        }

        public long getMaxMs() {
            return maxMs;
        }

        public void setMaxMs(long maxMs) {
            this.maxMs = maxMs;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    public static class Storm {
        private int windowSeconds = 300;
        private int failureStormThreshold = 5;
        private int failureWatchThreshold = 3;
        private int queueGrowthWatchThreshold = 5;

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getFailureStormThreshold() {
            return failureStormThreshold;
        }

        public void setFailureStormThreshold(int failureStormThreshold) {
            this.failureStormThreshold = failureStormThreshold;
        }

        public int getFailureWatchThreshold() {
            return failureWatchThreshold;
        }

        public void setFailureWatchThreshold(int failureWatchThreshold) {
            this.failureWatchThreshold = failureWatchThreshold;
        }

        public int getQueueGrowthWatchThreshold() {
            return queueGrowthWatchThreshold;
        }

        public void setQueueGrowthWatchThreshold(int queueGrowthWatchThreshold) {
            this.queueGrowthWatchThreshold = queueGrowthWatchThreshold;
        }
    }

    public static class Coordinator {
        /** 一个配额周期包含的调度周期数（用于把周期配额折算成每周期补充速率） */
        private int ticksPerPeriod = 10;

        public int getTicksPerPeriod() {
            return ticksPerPeriod;
        }

        public void setTicksPerPeriod(int ticksPerPeriod) {
            this.ticksPerPeriod = ticksPerPeriod;
        }
    }

    public static class BucketSpec {
        private int capacity;
        private int refillPerTick;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillPerTick() {
            return refillPerTick;
        }

        public void setRefillPerTick(int refillPerTick) {
            this.refillPerTick = refillPerTick;
        }
    }

    /** 容量画像与降级/恢复编排配置（路线 A：声明上限为硬边界，自动化调节不越界） */
    public static class Capacity {
        /** 依赖容量画像：dependencyCode -> 画像规格 */
        private Map<String, ProfileSpec> profiles = new LinkedHashMap<>();
        private Probe probe = new Probe();
        private Escalator escalator = new Escalator();
        private Recovery recovery = new Recovery();
        private Deadline deadline = new Deadline();

        public Map<String, ProfileSpec> getProfiles() {
            return profiles;
        }

        public void setProfiles(Map<String, ProfileSpec> profiles) {
            this.profiles = profiles;
        }

        public Probe getProbe() {
            return probe;
        }

        public void setProbe(Probe probe) {
            this.probe = probe;
        }

        public Escalator getEscalator() {
            return escalator;
        }

        public void setEscalator(Escalator escalator) {
            this.escalator = escalator;
        }

        public Recovery getRecovery() {
            return recovery;
        }

        public void setRecovery(Recovery recovery) {
            this.recovery = recovery;
        }

        public Deadline getDeadline() {
            return deadline;
        }

        public void setDeadline(Deadline deadline) {
            this.deadline = deadline;
        }
    }

    /** 单个依赖的容量画像规格 */
    public static class ProfileSpec {
        /** 预先声明的容量上限（每周期补充速率的安全边界），自动化调节永不越过 */
        private int declaredCeiling;
        /** 每来源最低服务份额（队列配额控制器每周期为每个来源保留的投放槽） */
        private int minSharePerSource = 1;

        public int getDeclaredCeiling() {
            return declaredCeiling;
        }

        public void setDeclaredCeiling(int declaredCeiling) {
            this.declaredCeiling = declaredCeiling;
        }

        public int getMinSharePerSource() {
            return minSharePerSource;
        }

        public void setMinSharePerSource(int minSharePerSource) {
            this.minSharePerSource = minSharePerSource;
        }
    }

    /** 依赖健康探针：探测预算上限（防止探测本身压垮依赖）与恢复确认阈值（抗抖动） */
    public static class Probe {
        /** 每个依赖每个调度周期最多探测次数 */
        private int maxPerTickPerDep = 1;
        /** 全部依赖每个调度周期探测总次数上限 */
        private int maxPerTickTotal = 3;
        /** 连续探测成功达到该次数才允许进入恢复爬坡（短暂恢复信号不被追认） */
        private int consecutiveOkToRecover = 2;

        public int getMaxPerTickPerDep() {
            return maxPerTickPerDep;
        }

        public void setMaxPerTickPerDep(int maxPerTickPerDep) {
            this.maxPerTickPerDep = maxPerTickPerDep;
        }

        public int getMaxPerTickTotal() {
            return maxPerTickTotal;
        }

        public void setMaxPerTickTotal(int maxPerTickTotal) {
            this.maxPerTickTotal = maxPerTickTotal;
        }

        public int getConsecutiveOkToRecover() {
            return consecutiveOkToRecover;
        }

        public void setConsecutiveOkToRecover(int consecutiveOkToRecover) {
            this.consecutiveOkToRecover = consecutiveOkToRecover;
        }
    }

    /** 降级编排器：按连续失败周期数逐级收紧（局部限流，只作用于故障依赖自身） */
    public static class Escalator {
        /** 连续失败周期达到该值 → DEGRADED（生效上限减半） */
        private int failureTicksToDegrade = 2;
        /** 连续失败周期达到该值 → DOWN（生效上限置零、冻结本地桶、开始受预算约束的探测） */
        private int failureTicksToDown = 3;
        /** DEGRADED 时生效上限 = max(1, floor(declared × factor)) */
        private double degradedFactor = 0.5;

        public int getFailureTicksToDegrade() {
            return failureTicksToDegrade;
        }

        public void setFailureTicksToDegrade(int failureTicksToDegrade) {
            this.failureTicksToDegrade = failureTicksToDegrade;
        }

        public int getFailureTicksToDown() {
            return failureTicksToDown;
        }

        public void setFailureTicksToDown(int failureTicksToDown) {
            this.failureTicksToDown = failureTicksToDown;
        }

        public double getDegradedFactor() {
            return degradedFactor;
        }

        public void setDegradedFactor(double degradedFactor) {
            this.degradedFactor = degradedFactor;
        }
    }

    /** 恢复节流器：保守爬坡（从小上限逐步上调至声明上限），任一探测失败即回落 */
    public static class Recovery {
        /** 恢复起步的生效上限 */
        private int initialCeiling = 1;
        /** 每驻留期满后的上调步长 */
        private int step = 1;
        /** 每级爬坡需要连续稳定（探测成功）的周期数 */
        private int dwellTicks = 1;

        public int getInitialCeiling() {
            return initialCeiling;
        }

        public void setInitialCeiling(int initialCeiling) {
            this.initialCeiling = initialCeiling;
        }

        public int getStep() {
            return step;
        }

        public void setStep(int step) {
            this.step = step;
        }

        public int getDwellTicks() {
            return dwellTicks;
        }

        public void setDwellTicks(int dwellTicks) {
            this.dwellTicks = dwellTicks;
        }
    }

    /** 截止风险估计器：清算截止时刻与临期窗口 */
    public static class Deadline {
        /** 缺省清算截止：清算日当日该时刻（小时，0-23） */
        private int cutoffHour = 20;
        /** 距截止不足该分钟数视为临期（ urgency 系数上调） */
        private long urgentWindowMinutes = 120;

        public int getCutoffHour() {
            return cutoffHour;
        }

        public void setCutoffHour(int cutoffHour) {
            this.cutoffHour = cutoffHour;
        }

        public long getUrgentWindowMinutes() {
            return urgentWindowMinutes;
        }

        public void setUrgentWindowMinutes(long urgentWindowMinutes) {
            this.urgentWindowMinutes = urgentWindowMinutes;
        }
    }
}
