package com.example.clearing.web;

import com.example.clearing.domain.QuotaGrant;
import com.example.clearing.domain.TokenBucket;
import com.example.clearing.repo.QuotaGrantRepository;
import com.example.clearing.service.TokenBucketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "令牌桶与配额证据")
@RestController
@RequestMapping("/api")
public class BucketController {

    private final TokenBucketService bucketService;
    private final QuotaGrantRepository quotaRepository;

    public BucketController(TokenBucketService bucketService, QuotaGrantRepository quotaRepository) {
        this.bucketService = bucketService;
        this.quotaRepository = quotaRepository;
    }

    @Operation(summary = "依赖本地令牌桶状态")
    @GetMapping("/buckets")
    public List<TokenBucket> buckets() {
        return bucketService.list();
    }

    @Operation(summary = "协调器配额证据", description = "按依赖 × 来源 × 金额优先级记录的周期配额授予")
    @GetMapping("/quota/evidence")
    public List<QuotaGrant> quotaEvidence(@RequestParam(defaultValue = "50") int limit) {
        return quotaRepository.findByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(limit, 200)));
    }
}
