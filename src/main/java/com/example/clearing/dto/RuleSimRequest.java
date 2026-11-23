package com.example.clearing.dto;

import com.example.clearing.service.DownstreamSimulator;
import jakarta.validation.constraints.NotNull;

public record RuleSimRequest(@NotNull DownstreamSimulator.RuleMode mode) {
}
