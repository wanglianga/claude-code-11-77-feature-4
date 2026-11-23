package com.example.clearing.service;

import com.example.clearing.domain.CapacityEventType;
import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.domain.DepHealth;
import com.example.clearing.domain.DependencyEdge;
import com.example.clearing.domain.DependencyNode;
import com.example.clearing.domain.ProfileState;
import com.example.clearing.repo.DependencyEdgeRepository;
import com.example.clearing.repo.DependencyNodeRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 依赖状态图：维护目录、待遇规则、基金网关、清算数据库、来源批次节点及其恢复条件。
 * 人工健康度变更是操作员权威：写入容量画像的 manual_override 标记并立即按声明口径生效
 * （DOWN→生效上限 0 冻结桶；HEALTHY→恢复声明上限；DEGRADED→减半），
 * 在人工覆盖期间自动化降级编排与健康探针不接管该依赖。
 */
@Service
public class DependencyGraphService {

    private final DependencyNodeRepository nodeRepository;
    private final DependencyEdgeRepository edgeRepository;
    private final CapacityProfileService profileService;
    private final DegradationOrchestrator orchestrator;

    public DependencyGraphService(DependencyNodeRepository nodeRepository,
            DependencyEdgeRepository edgeRepository,
            CapacityProfileService profileService,
            DegradationOrchestrator orchestrator) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.profileService = profileService;
        this.orchestrator = orchestrator;
    }

    public List<DependencyNode> nodes() {
        return nodeRepository.findAll();
    }

    public List<DependencyEdge> edges() {
        return edgeRepository.findAll();
    }

    public DependencyNode node(String code) {
        return nodeRepository.findById(code).orElse(null);
    }

    public boolean isDown(String code) {
        return nodeRepository.findById(code)
                .map(n -> n.getHealth() == DepHealth.DOWN)
                .orElse(false);
    }

    /**
     * 更新依赖健康度（人工覆盖）。
     * DOWN → 生效上限置零、冻结该依赖本地令牌桶（停止放行、停止补充）；
     * HEALTHY → 恢复声明上限、解冻（不补发冻结期间的尝试）；
     * DEGRADED → 生效上限减半。
     * 无容量画像的节点（目录、来源批次）只更新图状态。
     */
    @Transactional
    public DependencyNode updateHealth(String code, DepHealth health, String reason) {
        DependencyNode node = nodeRepository.findById(code)
                .orElseThrow(() -> new IllegalArgumentException("未知依赖: " + code));
        node.setHealth(health);
        node.setUpdatedAt(Instant.now());
        nodeRepository.save(node);

        CapacityProfile profile = profileService.get(code);
        if (profile != null) {
            profile.setManualOverride(true);
            switch (health) {
                case DOWN -> profileService.transition(profile, ProfileState.DOWN, 0,
                        CapacityEventType.MANUAL_OVERRIDE,
                        "人工覆盖为 DOWN：" + reason + "；生效上限置零并冻结本地桶，自动化编排暂不接管");
                case HEALTHY -> profileService.transition(profile, ProfileState.NORMAL,
                        profile.getDeclaredCeiling(), CapacityEventType.MANUAL_OVERRIDE,
                        "人工覆盖为 HEALTHY：" + reason + "；恢复声明上限 " + profile.getDeclaredCeiling()
                                + "/周期（操作员权威，立即生效，不补发故障期间尝试）");
                case DEGRADED -> profileService.transition(profile, ProfileState.DEGRADED,
                        orchestrator.degradedCeiling(profile), CapacityEventType.MANUAL_OVERRIDE,
                        "人工覆盖为 DEGRADED：" + reason + "；生效上限减半至 "
                                + orchestrator.degradedCeiling(profile) + "/周期");
            }
        }
        return node;
    }

    @Transactional
    public void resetHealth() {
        for (DependencyNode node : nodeRepository.findAll()) {
            node.setHealth(DepHealth.HEALTHY);
            node.setUpdatedAt(Instant.now());
            nodeRepository.save(node);
        }
    }
}
