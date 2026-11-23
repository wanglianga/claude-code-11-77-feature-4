package com.example.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 依赖状态图的边：fromCode 依赖 toCode */
@Entity
@Table(name = "dependency_edge")
public class DependencyEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_code", nullable = false, length = 64)
    private String fromCode;

    @Column(name = "to_code", nullable = false, length = 64)
    private String toCode;

    @Column(name = "edge_label", length = 256)
    private String edgeLabel;

    public DependencyEdge() {
    }

    public DependencyEdge(String fromCode, String toCode, String edgeLabel) {
        this.fromCode = fromCode;
        this.toCode = toCode;
        this.edgeLabel = edgeLabel;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFromCode() {
        return fromCode;
    }

    public void setFromCode(String fromCode) {
        this.fromCode = fromCode;
    }

    public String getToCode() {
        return toCode;
    }

    public void setToCode(String toCode) {
        this.toCode = toCode;
    }

    public String getEdgeLabel() {
        return edgeLabel;
    }

    public void setEdgeLabel(String edgeLabel) {
        this.edgeLabel = edgeLabel;
    }
}
