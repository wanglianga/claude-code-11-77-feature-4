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

/** 失败判定记录：每次失败的分类结果，可复核 */
@Entity
@Table(name = "failure_record", indexes = {
        @Index(name = "idx_failure_task", columnList = "task_id"),
        @Index(name = "idx_failure_dep_time", columnList = "dependency_code, created_at")
})
public class FailureRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "dependency_code", nullable = false, length = 64)
    private String dependencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_type", nullable = false, length = 24)
    private FailureType failureType;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public FailureRecord() {
    }

    public FailureRecord(Long taskId, String dependencyCode, FailureType failureType, String detail) {
        this.taskId = taskId;
        this.dependencyCode = dependencyCode;
        this.failureType = failureType;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getDependencyCode() {
        return dependencyCode;
    }

    public void setDependencyCode(String dependencyCode) {
        this.dependencyCode = dependencyCode;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    public void setFailureType(FailureType failureType) {
        this.failureType = failureType;
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
