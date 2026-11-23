package com.example.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 依赖本地令牌桶。每个依赖一个桶，按调度周期定额补充；
 * 依赖 DOWN 时冻结：不补充、不放行；恢复后从冻结时余额继续，
 * 不补发故障期间的全部尝试。
 */
@Entity
@Table(name = "token_bucket")
public class TokenBucket {

    @Id
    @Column(name = "dependency_code", length = 64)
    private String dependencyCode;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "tokens", nullable = false)
    private int tokens;

    /** 每个调度周期补充的令牌数 */
    @Column(name = "refill_per_tick", nullable = false)
    private int refillPerTick;

    @Column(name = "frozen", nullable = false)
    private boolean frozen = false;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "last_refill_at")
    private Instant lastRefillAt;

    @Column(name = "total_consumed", nullable = false)
    private long totalConsumed = 0;

    @Column(name = "total_refilled", nullable = false)
    private long totalRefilled = 0;

    public TokenBucket() {
    }

    public TokenBucket(String dependencyCode, int capacity, int refillPerTick) {
        this.dependencyCode = dependencyCode;
        this.capacity = capacity;
        this.refillPerTick = refillPerTick;
        this.tokens = capacity;
    }

    public String getDependencyCode() {
        return dependencyCode;
    }

    public void setDependencyCode(String dependencyCode) {
        this.dependencyCode = dependencyCode;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getTokens() {
        return tokens;
    }

    public void setTokens(int tokens) {
        this.tokens = tokens;
    }

    public int getRefillPerTick() {
        return refillPerTick;
    }

    public void setRefillPerTick(int refillPerTick) {
        this.refillPerTick = refillPerTick;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(Instant frozenAt) {
        this.frozenAt = frozenAt;
    }

    public Instant getLastRefillAt() {
        return lastRefillAt;
    }

    public void setLastRefillAt(Instant lastRefillAt) {
        this.lastRefillAt = lastRefillAt;
    }

    public long getTotalConsumed() {
        return totalConsumed;
    }

    public void setTotalConsumed(long totalConsumed) {
        this.totalConsumed = totalConsumed;
    }

    public long getTotalRefilled() {
        return totalRefilled;
    }

    public void setTotalRefilled(long totalRefilled) {
        this.totalRefilled = totalRefilled;
    }
}
