package com.example.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 依赖状态图节点：目录、待遇规则、基金网关、来源批次 */
@Entity
@Table(name = "dependency_node")
public class DependencyNode {

    @Id
    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 16)
    private NodeType nodeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "health", nullable = false, length = 16)
    private DepHealth health = DepHealth.HEALTHY;

    /** 恢复条件：该依赖被认为恢复所需满足的条件描述 */
    @Column(name = "recovery_condition", length = 512)
    private String recoveryCondition;

    @Column(name = "dep_version", length = 64)
    private String depVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public DependencyNode() {
    }

    public DependencyNode(String code, String name, NodeType nodeType, String recoveryCondition, String depVersion) {
        this.code = code;
        this.name = name;
        this.nodeType = nodeType;
        this.recoveryCondition = recoveryCondition;
        this.depVersion = depVersion;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public void setNodeType(NodeType nodeType) {
        this.nodeType = nodeType;
    }

    public DepHealth getHealth() {
        return health;
    }

    public void setHealth(DepHealth health) {
        this.health = health;
    }

    public String getRecoveryCondition() {
        return recoveryCondition;
    }

    public void setRecoveryCondition(String recoveryCondition) {
        this.recoveryCondition = recoveryCondition;
    }

    public String getDepVersion() {
        return depVersion;
    }

    public void setDepVersion(String depVersion) {
        this.depVersion = depVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
