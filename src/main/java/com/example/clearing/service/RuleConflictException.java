package com.example.clearing.service;

/** 待遇规则冲突：多条规则命中且互斥，需人工裁定 */
public class RuleConflictException extends RuntimeException {

    public RuleConflictException(String message) {
        super(message);
    }
}
