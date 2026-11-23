package com.example.clearing.web;

import com.example.clearing.domain.TaskStatus;
import com.example.clearing.dto.ScenarioResult;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.LedgerEntryRepository;
import com.example.clearing.service.DemoScenarioService;
import com.example.clearing.service.StormObserver;
import com.example.clearing.service.TokenBucketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "验收演练")
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoScenarioService scenarioService;
    private final ClearingTaskRepository taskRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final TokenBucketService bucketService;
    private final StormObserver stormObserver;

    public DemoController(DemoScenarioService scenarioService,
            ClearingTaskRepository taskRepository,
            LedgerEntryRepository ledgerRepository,
            TokenBucketService bucketService,
            StormObserver stormObserver) {
        this.scenarioService = scenarioService;
        this.taskRepository = taskRepository;
        this.ledgerRepository = ledgerRepository;
        this.bucketService = bucketService;
        this.stormObserver = stormObserver;
    }

    @Operation(summary = "演练复位", description = "清空任务/账本/事件/风暴/配额数据，恢复依赖健康与令牌桶")
    @PostMapping("/reset")
    public Map<String, String> reset() {
        scenarioService.reset();
        return Map.of("status", "reset-ok");
    }

    @Operation(summary = "执行验收场景",
            description = "hospital-batch / pharmacy-return / catalog-conflict / rule-degradation / "
                    + "fund-pending-confirm / content-conflict / storm-watch / "
                    + "month-end-local-throttle / month-end-priority-jump / month-end-min-service / "
                    + "month-end-conservative-recovery")
    @PostMapping("/scenario/{name}")
    public ScenarioResult runScenario(@PathVariable String name) {
        return scenarioService.run(name);
    }

    @Operation(summary = "运行概览")
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> view = new LinkedHashMap<>();
        Map<TaskStatus, Long> byStatus = new EnumMap<>(TaskStatus.class);
        for (TaskStatus status : TaskStatus.values()) {
            byStatus.put(status, (long) taskRepository.findByStatus(status).size());
        }
        view.put("tasksByStatus", byStatus);
        view.put("ledgerEntries", ledgerRepository.count());
        view.put("buckets", bucketService.list());
        view.put("storm", stormObserver.latest());
        return view;
    }
}
