package com.example.clearing.service;

/** 报文无法解析 */
public class BadPayloadException extends RuntimeException {

    public BadPayloadException(String message) {
        super(message);
    }
}
