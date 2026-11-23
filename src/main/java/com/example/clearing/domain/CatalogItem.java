package com.example.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 医保目录项（本地同步副本，目录匹配用） */
@Entity
@Table(name = "catalog_item")
public class CatalogItem {

    @Id
    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "catalog_version", nullable = false, length = 32)
    private String catalogVersion;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public CatalogItem() {
    }

    public CatalogItem(String code, String name, String catalogVersion) {
        this.code = code;
        this.name = name;
        this.catalogVersion = catalogVersion;
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

    public String getCatalogVersion() {
        return catalogVersion;
    }

    public void setCatalogVersion(String catalogVersion) {
        this.catalogVersion = catalogVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
