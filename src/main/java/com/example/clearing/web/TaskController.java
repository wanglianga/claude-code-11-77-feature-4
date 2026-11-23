package com.example.clearing.web;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.dto.ManualResolveRequest;
import com.example.clearing.dto.RegisterTaskRequest;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.service.ManualResolutionService;
import com.example.clearing.service.RecoveryExplainer;
import com.example.clearing.service.TaskRegistrar;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "任务登记与解释")
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskRegistrar registrar;
    private final ClearingTaskRepository taskRepository;
    private final RecoveryExplainer explainer;
    private final ManualResolutionService manualResolution;

    public TaskController(TaskRegistrar registrar,
            ClearingTaskRepository taskRepository,
            RecoveryExplainer explainer,
            ManualResolutionService manualResolution) {
        this.registrar = registrar;
        this.taskRepository = taskRepository;
        this.explainer = explainer;
        this.manualResolution = manualResolution;
    }

    @Operation(summary = "登记清算任务", description = "同业务键+同版本+同内容幂等吸收；同业务键+同版本+不同内容生成内容冲突，不发放令牌；"
            + "可显式指定清算截止时间 deadlineAt 供截止风险估计")
    @PostMapping
    public TaskRegistrar.RegisterResult register(@Valid @RequestBody RegisterTaskRequest request) {
        return registrar.register(request.batchId(), request.clearingDate(), request.businessKey(),
                request.billVersion(), request.sourceType(), request.amount(), request.payload(),
                request.deadlineAt());
    }

    @Operation(summary = "任务列表")
    @GetMapping
    public List<ClearingTask> list(@RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String dependencyCode) {
        if (status != null) {
            return taskRepository.findByStatus(status);
        }
        if (dependencyCode != null) {
            return taskRepository.findByDependencyCode(dependencyCode);
        }
        return taskRepository.findAll();
    }

    @Operation(summary = "任务详情")
    @GetMapping("/{id}")
    public ClearingTask get(@PathVariable Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    @Operation(summary = "恢复解释", description = "说明任务为何等待、被限流、重投或升级人工，含完整决策时间线")
    @GetMapping("/{id}/explanation")
    public RecoveryExplainer.Explanation explanation(@PathVariable Long id) {
        return explainer.explain(id);
    }

    @Operation(summary = "人工裁定", description = "REQUEUE 重新排队 / CANCEL 取消 / KEEP_ORIGINAL 保留原清单 / OVERRIDE 以新内容覆盖")
    @PostMapping("/{id}/manual-resolve")
    public ClearingTask manualResolve(@PathVariable Long id, @Valid @RequestBody ManualResolveRequest request) {
        return manualResolution.resolve(id, request.action(), request.note());
    }
}
