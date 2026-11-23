package com.example.clearing.dto;

import com.example.clearing.domain.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 任务登记请求；deadlineAt 可空，缺省为清算日当日截止时刻（截止风险估计器口径） */
public record RegisterTaskRequest(
        @NotBlank(message = "输入批次号不能为空") String batchId,
        @NotNull(message = "清算日不能为空") LocalDate clearingDate,
        @NotBlank(message = "业务键不能为空") String businessKey,
        @NotBlank(message = "清单版本不能为空") String billVersion,
        @NotNull(message = "来源类型不能为空") SourceType sourceType,
        @NotNull @Positive(message = "受影响金额必须为正") BigDecimal amount,
        @NotBlank(message = "报文 payload 不能为空") String payload,
        Instant deadlineAt) {
}
