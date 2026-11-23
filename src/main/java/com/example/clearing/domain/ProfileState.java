package com.example.clearing.domain;

/**
 * 容量画像状态机：
 * NORMAL →（连续失败滴答）→ DEGRADED →（持续失败）→ DOWN
 * DOWN →（探针连续 OK）→ RECOVERING →（爬坡到达声明上限）→ NORMAL
 */
public enum ProfileState {
    /** 正常：生效上限 = 声明上限 */
    NORMAL,
    /** 降级：生效上限减半（局部限流，仅收紧本依赖） */
    DEGRADED,
    /** 不可用：生效上限 0，本地令牌桶冻结，探针在安全预算内探测 */
    DOWN,
    /** 恢复中：探针确认信号后从下限保守爬坡，任一探测失败即回落 DOWN */
    RECOVERING
}
