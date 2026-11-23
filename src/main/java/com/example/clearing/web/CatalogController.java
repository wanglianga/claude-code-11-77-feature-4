package com.example.clearing.web;

import com.example.clearing.domain.CatalogItem;
import com.example.clearing.dto.CatalogItemRequest;
import com.example.clearing.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "医保目录")
@RestController
@RequestMapping("/api/catalog/items")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(summary = "目录项列表")
    @GetMapping
    public List<CatalogItem> list() {
        return catalogService.list();
    }

    @Operation(summary = "目录同步（新增/更新目录项）", description = "补齐缺失目录项后，等待中的任务下一调度周期自动提升")
    @PostMapping
    public CatalogItem add(@Valid @RequestBody CatalogItemRequest request) {
        String version = request.catalogVersion() == null || request.catalogVersion().isBlank()
                ? "2026.11"
                : request.catalogVersion();
        return catalogService.add(request.code(), request.name(), version);
    }
}
