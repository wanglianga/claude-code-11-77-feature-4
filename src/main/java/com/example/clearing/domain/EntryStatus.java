package com.example.clearing.domain;

public enum EntryStatus {
    /** 已入账（扣款为终态） */
    POSTED,
    /** 回传已受理，回执待确认 */
    PENDING_CONFIRM,
    /** 回执已确认 */
    CONFIRMED
}
