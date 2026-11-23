package com.example.clearing.dto;

import com.example.clearing.domain.DepHealth;
import jakarta.validation.constraints.NotNull;

public record HealthRequest(@NotNull DepHealth health, String reason) {
}
