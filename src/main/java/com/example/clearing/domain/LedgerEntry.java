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
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 幂等账本：以清单版本 + 清算操作号约束入账与回传。
 * 同一 (bill_version, operation_no) 只能有一条分录；
 * 基金回传在回执确认前保持 PENDING_CONFIRM，只按操作号查询，不重新扣款。
 */
@Entity
@Table(name = "ledger_entry",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ledger_op", columnNames = {"operation_no"}),
                @UniqueConstraint(name = "uk_ledger_ver_op", columnNames = {"bill_version", "operation_no"})
        },
        indexes = {
                @Index(name = "idx_ledger_biz", columnList = "business_key"),
                @Index(name = "idx_ledger_status", columnList = "status")
        })
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 清算操作号：CD-{businessKey}-{billVersion} / FR-{businessKey}-{billVersion} */
    @Column(name = "operation_no", nullable = false, length = 192)
    private String operationNo;

    @Column(name = "business_key", nullable = false, length = 128)
    private String businessKey;

    @Column(name = "bill_version", nullable = false, length = 32)
    private String billVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private EntryType entryType;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EntryStatus status;

    /** 基金网关是否已受理（受理后回执延迟只查不扣） */
    @Column(name = "gateway_accepted", nullable = false)
    private boolean gatewayAccepted = false;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOperationNo() {
        return operationNo;
    }

    public void setOperationNo(String operationNo) {
        this.operationNo = operationNo;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public String getBillVersion() {
        return billVersion;
    }

    public void setBillVersion(String billVersion) {
        this.billVersion = billVersion;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public void setEntryType(EntryType entryType) {
        this.entryType = entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public EntryStatus getStatus() {
        return status;
    }

    public void setStatus(EntryStatus status) {
        this.status = status;
    }

    public boolean isGatewayAccepted() {
        return gatewayAccepted;
    }

    public void setGatewayAccepted(boolean gatewayAccepted) {
        this.gatewayAccepted = gatewayAccepted;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
