package com.example.clearing.dto;

import jakarta.validation.constraints.NotBlank;

public record CatalogItemRequest(@NotBlank String code, @NotBlank String name, String catalogVersion) {
}
