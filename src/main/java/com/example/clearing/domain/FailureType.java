package com.example.clearing.domain;

/** 失败判定器输出的可复核失败类别 */
public enum FailureType {
    /** 技术瞬时故障：超时、连接失败等，可退避重试 */
    TECH_TRANSIENT,
    /** 目录等待：目录项缺失或目录服务不可用 */
    CATALOG_WAIT,
    /** 待遇规则冲突：多条规则命中且互斥 */
    RULE_CONFLICT,
    /** 内容不一致：同业务键不同内容 */
    CONTENT_MISMATCH,
    /** 人工裁定：无法自动归类或重试耗尽 */
    MANUAL_ADJUDICATION
}
