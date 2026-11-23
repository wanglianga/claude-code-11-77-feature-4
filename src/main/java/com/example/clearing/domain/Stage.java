package com.example.clearing.domain;

/** 清算任务处理阶段：待遇计算（目录匹配+规则计算+入账）→ 基金回传 → 完成 */
public enum Stage {
    CALC,
    RETURN,
    DONE
}
