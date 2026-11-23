package com.example.clearing.domain;

/** 清算任务状态机 */
public enum TaskStatus {
    /** 已登记，待进入调度 */
    REGISTERED,
    /** 同业务键不同内容 → 内容冲突，不发放令牌，等待人工裁定 */
    CONTENT_CONFLICT,
    /** 等待依赖（目录缺失 / 目录服务不可用） */
    WAITING_DEPENDENCY,
    /** 就绪，等待令牌与退避窗口 */
    READY,
    /** 瞬时故障，按退避时间等待重试 */
    RETRY_SCHEDULED,
    /** 已投放执行中 */
    DISPATCHED,
    /** 基金回传已受理，确认回执延迟，按操作号待确认 */
    PENDING_CONFIRM,
    /** 回执确认，终态 */
    CONFIRMED,
    /** 人工裁定（规则冲突 / 多次重试未果） */
    MANUAL_REVIEW,
    /** 人工取消，终态 */
    CANCELLED,
    /** 重试耗尽，终态 */
    FAILED_FINAL
}
