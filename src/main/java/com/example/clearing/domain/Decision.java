package com.example.clearing.domain;

/** 恢复解释器使用的决策类型（任务时间线上的每一次状态变化原因） */
public enum Decision {
    REGISTERED,
    DUPLICATE,
    CONTENT_CONFLICT,
    PROMOTED_READY,
    WAIT_CATALOG,
    WAIT_DEPENDENCY_DOWN,
    THROTTLED,
    BACKOFF,
    DISPATCHED,
    STAGE_ADVANCED,
    PENDING_CONFIRM,
    RECEIPT_QUERY_FAILED,
    CONFIRMED,
    RETRY_SCHEDULED,
    ESCALATED_MANUAL,
    MANUAL_RESOLVED,
    CANCELLED
}
