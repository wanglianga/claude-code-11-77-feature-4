package com.example.clearing.domain;

/** 容量事件类型：容量画像库与降级/恢复编排的可审计轨迹 */
public enum CapacityEventType {
    /** 画像种子初始化 */
    PROFILE_SEEDED,
    /** 降级编排：连续失败滴答达到阈值，生效上限减半 */
    ENTER_DEGRADED,
    /** 降级编排：失败持续，生效上限置零并冻结本地桶（局部限流，不影响其他依赖） */
    ENTER_DOWN,
    /** 探针在安全预算内执行且成功 */
    PROBE_OK,
    /** 探针执行且失败 */
    PROBE_FAIL,
    /** 探针因探测预算（每依赖/每周期/全局上限）被跳过 */
    PROBE_SKIPPED_BUDGET,
    /** 恢复节流器：探针连续 OK 达到确认数，进入恢复爬坡（保守起点） */
    RECOVERY_STARTED,
    /** 恢复节流器：爬坡一步（生效上限上调，仍不超过声明上限） */
    RAMP_STEP,
    /** 恢复节流器：爬坡到达声明上限，画像恢复 NORMAL */
    RECOVERED,
    /** 恢复节流器：恢复期间探测失败/信号抖动，回落 DOWN（保守恢复：不追认短暂信号） */
    RECOVERY_ABORTED,
    /** 人工健康度覆盖：操作员权威优先于自动化编排 */
    MANUAL_OVERRIDE
}
