package com.example.clearing.dto;

import com.example.clearing.service.ManualResolutionService;
import jakarta.validation.constraints.NotNull;

public record ManualResolveRequest(@NotNull ManualResolutionService.Action action, String note) {
}
