package com.example.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 协调器配额证据：每个周期按依赖 × 来源 × 金额优先级记录
 * 需求、授予令牌数与理由，供审计与跨队列公平性复核。
 */
@Entity
@Table(name = "quota_grant", indexes = {
        @Index(name = "idx_quota_dep", columnList = "dependency_code"),
        @Index(name = "idx_quota_created", columnList = "created_at")
})
public class QuotaGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dependency_code", nullable = false, length = 64)
    private String dependencyCode;

    @Column(name = "source_type", nullable = false, length = 16)
    private String sourceType;

    /** 金额优先级带：HIGH / MEDIUM / LOW */
    @Column(name = "priority_band", nullable = false, length = 8)
    private String priorityBand;

    /** 该组排队任务数 */
    @Column(name = "demand", nullable = false)
    private int demand;

    /** 本周期授予的尝试令牌数（= 保留份额授予 + 公共池授予） */
    @Column(name = "granted", nullable = false)
    private int granted;

    /** 其中：每来源最低服务份额（保留份额）内授予的数量 */
    @Column(name = "reserved_granted", nullable = false)
    private int reservedGranted = 0;

    /** 其中：公共池按风险分排序授予（可追溯插队）的数量 */
    @Column(name = "bonus_granted", nullable = false)
    private int bonusGranted = 0;

    /** 该组本周期见到的最高截止风险分（可追溯优先级的证据） */
    @Column(name = "max_risk_score", nullable = false)
    private double maxRiskScore = 0.0;

    @Column(name = "period_label", nullable = false, length = 64)
    private String periodLabel;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDependencyCode() {
        return dependencyCode;
    }

    public void setDependencyCode(String dependencyCode) {
        this.dependencyCode = dependencyCode;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getPriorityBand() {
        return priorityBand;
    }

    public void setPriorityBand(String priorityBand) {
        this.priorityBand = priorityBand;
    }

    public int getDemand() {
        return demand;
    }

    public void setDemand(int demand) {
        this.demand = demand;
    }

    public int getGranted() {
        return granted;
    }

    public void setGranted(int granted) {
        this.granted = granted;
    }

    public int getReservedGranted() {
        return reservedGranted;
    }

    public void setReservedGranted(int reservedGranted) {
        this.reservedGranted = reservedGranted;
    }

    public int getBonusGranted() {
        return bonusGranted;
    }

    public void setBonusGranted(int bonusGranted) {
        this.bonusGranted = bonusGranted;
    }

    public double getMaxRiskScore() {
        return maxRiskScore;
    }

    public void setMaxRiskScore(double maxRiskScore) {
        this.maxRiskScore = maxRiskScore;
    }

    public String getPeriodLabel() {
        return periodLabel;
    }

    public void setPeriodLabel(String periodLabel) {
        this.periodLabel = periodLabel;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
