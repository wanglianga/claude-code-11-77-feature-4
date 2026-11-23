package com.example.clearing.domain;

public enum NodeType {
    CATALOG,
    RULE,
    FUND,
    SOURCE,
    /** 清算数据库（幂等账本所在库，容量画像与本地桶保护其写入） */
    DATABASE
}
