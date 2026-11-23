package com.example.clearing.service;

/** 技术瞬时故障（超时、连接失败），可退避重试 */
public class TransientFailureException extends RuntimeException {

    public TransientFailureException(String message) {
        super(message);
    }
}
