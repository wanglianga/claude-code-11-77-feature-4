package com.example.clearing.web;

import com.example.clearing.domain.DependencyEdge;
import com.example.clearing.domain.DependencyNode;
import com.example.clearing.dto.HealthRequest;
import com.example.clearing.service.DependencyGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "依赖状态图")
@RestController
@RequestMapping("/api/dependencies")
public class DependencyController {

    private final DependencyGraphService graphService;

    public DependencyController(DependencyGraphService graphService) {
        this.graphService = graphService;
    }

    public record GraphView(List<DependencyNode> nodes, List<DependencyEdge> edges) {
    }

    @Operation(summary = "依赖状态图", description = "目录、待遇规则、基金网关、来源批次节点及其恢复条件与依赖边")
    @GetMapping
    public GraphView graph() {
        return new GraphView(graphService.nodes(), graphService.edges());
    }

    @Operation(summary = "更新依赖健康度", description = "DOWN 冻结对应本地令牌桶；HEALTHY 解冻（不补发）；DEGRADED 配额减半")
    @PostMapping("/{code}/health")
    public DependencyNode updateHealth(@PathVariable String code, @Valid @RequestBody HealthRequest request) {
        return graphService.updateHealth(code, request.health(),
                request.reason() == null ? "人工更新" : request.reason());
    }
}
