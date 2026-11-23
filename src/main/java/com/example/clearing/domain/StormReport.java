package com.example.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** 风暴观测报告：队列增长、令牌消耗、同依赖失败聚集 */
@Entity
@Table(name = "storm_report", indexes = {
        @Index(name = "idx_storm_dep", columnList = "dependency_code"),
        @Index(name = "idx_storm_created", columnList = "created_at")
})
public class StormReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dependency_code", nullable = false, length = 64)
    private String dependencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "storm_level", nullable = false, length = 12)
    private StormLevel stormLevel;

    @Column(name = "queue_depth", nullable = false)
    private int queueDepth;

    @Column(name = "queue_growth", nullable = false)
    private int queueGrowth;

    @Column(name = "tokens_consumed_window", nullable = false)
    private long tokensConsumedWindow;

    @Column(name = "failures_window", nullable = false)
    private long failuresWindow;

    @Column(name = "summary", length = 512)
    private String summary;

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

    public StormLevel getStormLevel() {
        return stormLevel;
    }

    public void setStormLevel(StormLevel stormLevel) {
        this.stormLevel = stormLevel;
    }

    public int getQueueDepth() {
        return queueDepth;
    }

    public void setQueueDepth(int queueDepth) {
        this.queueDepth = queueDepth;
    }

    public int getQueueGrowth() {
        return queueGrowth;
    }

    public void setQueueGrowth(int queueGrowth) {
        this.queueGrowth = queueGrowth;
    }

    public long getTokensConsumedWindow() {
        return tokensConsumedWindow;
    }

    public void setTokensConsumedWindow(long tokensConsumedWindow) {
        this.tokensConsumedWindow = tokensConsumedWindow;
    }

    public long getFailuresWindow() {
        return failuresWindow;
    }

    public void setFailuresWindow(long failuresWindow) {
        this.failuresWindow = failuresWindow;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
