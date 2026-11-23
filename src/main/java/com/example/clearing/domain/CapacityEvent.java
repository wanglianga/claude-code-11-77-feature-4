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

/**
 * 容量事件：容量画像库上每一次探测、降级、爬坡、人工覆盖的审计记录。
 * 用于复核"局部限流是否只收紧故障依赖"、"恢复是否保守"、"探测是否在上限内"。
 */
@Entity
@Table(name = "capacity_event", indexes = {
        @Index(name = "idx_capevent_dep", columnList = "dependency_code"),
        @Index(name = "idx_capevent_created", columnList = "created_at")
})
public class CapacityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dependency_code", nullable = false, length = 64)
    private String dependencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private CapacityEventType eventType;

    /** 事件发生后的生效上限快照 */
    @Column(name = "effective_ceiling", nullable = false)
    private int effectiveCeiling;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public CapacityEvent() {
    }

    public CapacityEvent(String dependencyCode, CapacityEventType eventType, int effectiveCeiling, String detail) {
        this.dependencyCode = dependencyCode;
        this.eventType = eventType;
        this.effectiveCeiling = effectiveCeiling;
        this.detail = detail;
    }

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

    public CapacityEventType getEventType() {
        return eventType;
    }

    public void setEventType(CapacityEventType eventType) {
        this.eventType = eventType;
    }

    public int getEffectiveCeiling() {
        return effectiveCeiling;
    }

    public void setEffectiveCeiling(int effectiveCeiling) {
        this.effectiveCeiling = effectiveCeiling;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
