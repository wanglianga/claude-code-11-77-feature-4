package com.example.clearing.config;

import com.example.clearing.domain.CatalogItem;
import com.example.clearing.domain.DependencyEdge;
import com.example.clearing.domain.DependencyNode;
import com.example.clearing.domain.NodeType;
import com.example.clearing.repo.CatalogItemRepository;
import com.example.clearing.repo.DependencyEdgeRepository;
import com.example.clearing.repo.DependencyNodeRepository;
import com.example.clearing.service.CapacityProfileService;
import com.example.clearing.service.DependencyHealthProbe;
import com.example.clearing.service.TokenBucketService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动种子：依赖状态图节点/边、本地令牌桶、容量画像库、医保目录基线 */
@Component
public class DataSeeder implements ApplicationRunner {

    private final DependencyNodeRepository nodeRepository;
    private final DependencyEdgeRepository edgeRepository;
    private final CatalogItemRepository catalogRepository;
    private final TokenBucketService bucketService;
    private final CapacityProfileService profileService;
    private final ClearingProperties properties;

    public DataSeeder(DependencyNodeRepository nodeRepository,
            DependencyEdgeRepository edgeRepository,
            CatalogItemRepository catalogRepository,
            TokenBucketService bucketService,
            CapacityProfileService profileService,
            ClearingProperties properties) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.catalogRepository = catalogRepository;
        this.bucketService = bucketService;
        this.profileService = profileService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (nodeRepository.count() == 0) {
            nodeRepository.save(new DependencyNode("CATALOG", "医保目录服务", NodeType.CATALOG,
                    "目录同步完成且缺失目录项补齐", "2026.11"));
            nodeRepository.save(new DependencyNode("RULE_ENGINE", "待遇规则服务", NodeType.RULE,
                    "规则服务健康检查通过且规则版本一致", properties.getRuleVersion()));
            nodeRepository.save(new DependencyNode("FUND_GW", "基金回传网关", NodeType.FUND,
                    "基金网关可用且待确认回执清零", "1.0"));
            nodeRepository.save(new DependencyNode(DependencyHealthProbe.DEP_DB, "清算数据库", NodeType.DATABASE,
                    "数据库连接探测（SELECT 1）通过", "18"));
            nodeRepository.save(new DependencyNode("HOSPITAL_SRC", "医院结算批次源", NodeType.SOURCE,
                    "医院批次重传完成", "-"));
            nodeRepository.save(new DependencyNode("PHARMACY_SRC", "药店购药回传源", NodeType.SOURCE,
                    "药店回传链路恢复", "-"));

            edgeRepository.save(new DependencyEdge("RULE_ENGINE", "CATALOG", "待遇规则引用医保目录版本"));
            edgeRepository.save(new DependencyEdge("RULE_ENGINE", DependencyHealthProbe.DEP_DB,
                    "待遇计算结果写入幂等账本"));
            edgeRepository.save(new DependencyEdge("FUND_GW", "RULE_ENGINE", "基金回传依赖待遇计算结果"));
            edgeRepository.save(new DependencyEdge("HOSPITAL_SRC", "RULE_ENGINE", "医院批次清算依赖待遇规则"));
            edgeRepository.save(new DependencyEdge("HOSPITAL_SRC", "CATALOG", "医院清单需目录匹配"));
            edgeRepository.save(new DependencyEdge("PHARMACY_SRC", "RULE_ENGINE", "药店回传清算依赖待遇规则"));
        }
        bucketService.ensureBuckets();
        profileService.ensureProfiles();
        if (catalogRepository.count() == 0) {
            catalogRepository.save(new CatalogItem("A-001", "阿莫西林胶囊", "2026.11"));
            catalogRepository.save(new CatalogItem("B-002", "布洛芬缓释片", "2026.11"));
            catalogRepository.save(new CatalogItem("C-003", "胰岛素注射液", "2026.11"));
            catalogRepository.save(new CatalogItem("D-004", "维生素C片", "2026.11"));
        }
    }
}
