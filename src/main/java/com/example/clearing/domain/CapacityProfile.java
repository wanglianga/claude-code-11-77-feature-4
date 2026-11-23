package com.example.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 容量画像库：每个被保护依赖一行的容量画像。
 * 路线 A（预先声明容量上限 + 每来源最低服务份额）为骨干：
 *  - declaredCeiling 是预先声明的安全边界（每调度周期最大补充速率），任何自动化
 *    调节（探针闭环、恢复爬坡）都不会越过它；
 *  - effectiveCeiling 是降级编排器/恢复节流器在声明上限之内调节出的当前生效上限；
 *  - minSharePerSource 是队列配额控制器为每个来源保留的最低服务份额。
 * 画像同时记录探针统计与近期成功率（EMA），供审计与闭环判断。
 */
@Entity
@Table(name = "capacity_profile")
public class CapacityProfile {

    @Id
    @Column(name = "dependency_code", length = 64)
    private String dependencyCode;

    /** 预先声明的容量上限（每周期补充速率的安全边界），自动化调节永不越过 */
    @Column(name = "declared_ceiling", nullable = false)
    private int declaredCeiling;

    /** 当前生效上限（0..declaredCeiling），由降级编排器/恢复节流器调节 */
    @Column(name = "effective_ceiling", nullable = false)
    private int effectiveCeiling;

    /** 每来源最低服务份额：队列配额控制器每周期为每个有等待任务的来源保留的投放槽 */
    @Column(name = "min_share_per_source", nullable = false)
    private int minSharePerSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ProfileState state = ProfileState.NORMAL;

    /** 人工健康度覆盖：操作员通过依赖状态图显式设置健康度后为 true，自动化编排不接管 */
    @Column(name = "manual_override", nullable = false)
    private boolean manualOverride = false;

    /** 连续出现失败的调度周期数（降级编排的升级阶梯输入） */
    @Column(name = "consecutive_failure_ticks", nullable = false)
    private int consecutiveFailureTicks = 0;

    /** 连续探测成功次数（恢复确认阈值输入，抗抖动） */
    @Column(name = "consecutive_probe_ok", nullable = false)
    private int consecutiveProbeOk = 0;

    /** 本调度周期内已执行的探测次数（探测上限记账） */
    @Column(name = "probes_this_tick", nullable = false)
    private int probesThisTick = 0;

    @Column(name = "total_probes", nullable = false)
    private long totalProbes = 0;

    @Column(name = "total_probe_failures", nullable = false)
    private long totalProbeFailures = 0;

    /** 近期成功率画像（指数滑动平均，1.0=全部成功） */
    @Column(name = "recent_success_rate", nullable = false)
    private double recentSuccessRate = 1.0;

    /** 恢复爬坡已停留的周期数（恢复节流器驻留控制） */
    @Column(name = "ramp_dwell_ticks", nullable = false)
    private int rampDwellTicks = 0;

    @Column(name = "last_state_change_at", nullable = false)
    private Instant lastStateChangeAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public CapacityProfile() {
    }

    public CapacityProfile(String dependencyCode, int declaredCeiling, int minSharePerSource) {
        this.dependencyCode = dependencyCode;
        this.declaredCeiling = declaredCeiling;
        this.effectiveCeiling = declaredCeiling;
        this.minSharePerSource = minSharePerSource;
    }

    public String getDependencyCode() {
        return dependencyCode;
    }

    public void setDependencyCode(String dependencyCode) {
        this.dependencyCode = dependencyCode;
    }

    public int getDeclaredCeiling() {
        return declaredCeiling;
    }

    public void setDeclaredCeiling(int declaredCeiling) {
        this.declaredCeiling = declaredCeiling;
    }

    public int getEffectiveCeiling() {
        return effectiveCeiling;
    }

    public void setEffectiveCeiling(int effectiveCeiling) {
        this.effectiveCeiling = effectiveCeiling;
    }

    public int getMinSharePerSource() {
        return minSharePerSource;
    }

    public void setMinSharePerSource(int minSharePerSource) {
        this.minSharePerSource = minSharePerSource;
    }

    public ProfileState getState() {
        return state;
    }

    public void setState(ProfileState state) {
        this.state = state;
    }

    public boolean isManualOverride() {
        return manualOverride;
    }

    public void setManualOverride(boolean manualOverride) {
        this.manualOverride = manualOverride;
    }

    public int getConsecutiveFailureTicks() {
        return consecutiveFailureTicks;
    }

    public void setConsecutiveFailureTicks(int consecutiveFailureTicks) {
        this.consecutiveFailureTicks = consecutiveFailureTicks;
    }

    public int getConsecutiveProbeOk() {
        return consecutiveProbeOk;
    }

    public void setConsecutiveProbeOk(int consecutiveProbeOk) {
        this.consecutiveProbeOk = consecutiveProbeOk;
    }

    public int getProbesThisTick() {
        return probesThisTick;
    }

    public void setProbesThisTick(int probesThisTick) {
        this.probesThisTick = probesThisTick;
    }

    public long getTotalProbes() {
        return totalProbes;
    }

    public void setTotalProbes(long totalProbes) {
        this.totalProbes = totalProbes;
    }

    public long getTotalProbeFailures() {
        return totalProbeFailures;
    }

    public void setTotalProbeFailures(long totalProbeFailures) {
        this.totalProbeFailures = totalProbeFailures;
    }

    public double getRecentSuccessRate() {
        return recentSuccessRate;
    }

    public void setRecentSuccessRate(double recentSuccessRate) {
        this.recentSuccessRate = recentSuccessRate;
    }

    public int getRampDwellTicks() {
        return rampDwellTicks;
    }

    public void setRampDwellTicks(int rampDwellTicks) {
        this.rampDwellTicks = rampDwellTicks;
    }

    public Instant getLastStateChangeAt() {
        return lastStateChangeAt;
    }

    public void setLastStateChangeAt(Instant lastStateChangeAt) {
        this.lastStateChangeAt = lastStateChangeAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
