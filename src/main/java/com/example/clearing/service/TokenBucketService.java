package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import com.example.clearing.domain.TokenBucket;
import com.example.clearing.repo.TokenBucketRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 依赖本地令牌桶。
 * 关键不变量：
 *  - 冻结（依赖 DOWN）期间不补充、不放行；
 *  - 解冻后不补发冻结期间错过的补充，只从冻结余额继续按周期渐进补充。
 */
@Service
public class TokenBucketService {

    private final TokenBucketRepository bucketRepository;
    private final ClearingProperties properties;

    public TokenBucketService(TokenBucketRepository bucketRepository, ClearingProperties properties) {
        this.bucketRepository = bucketRepository;
        this.properties = properties;
    }

    @Transactional
    public void ensureBuckets() {
        properties.getBuckets().forEach((code, spec) -> {
            bucketRepository.findById(code).orElseGet(() ->
                    bucketRepository.save(new TokenBucket(code, spec.getCapacity(), spec.getRefillPerTick())));
        });
    }

    /** 重置为配置初始状态（演练复位用） */
    @Transactional
    public void resetAll() {
        properties.getBuckets().forEach((code, spec) -> {
            TokenBucket b = bucketRepository.findById(code)
                    .orElse(new TokenBucket(code, spec.getCapacity(), spec.getRefillPerTick()));
            b.setCapacity(spec.getCapacity());
            b.setRefillPerTick(spec.getRefillPerTick());
            b.setTokens(spec.getCapacity());
            b.setFrozen(false);
            b.setFrozenAt(null);
            b.setLastRefillAt(Instant.now());
            b.setTotalConsumed(0);
            b.setTotalRefilled(0);
            bucketRepository.save(b);
        });
    }

    /** 每调度周期补充一次；冻结的桶跳过（不补充） */
    @Transactional
    public void refillAll() {
        for (TokenBucket b : bucketRepository.findAll()) {
            if (b.isFrozen()) {
                continue;
            }
            int before = b.getTokens();
            int after = Math.min(b.getCapacity(), before + b.getRefillPerTick());
            if (after != before) {
                b.setTokens(after);
                b.setTotalRefilled(b.getTotalRefilled() + (after - before));
            }
            b.setLastRefillAt(Instant.now());
            bucketRepository.save(b);
        }
    }

    /** 尝试消费 1 个令牌；冻结或余额不足返回 false */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean tryConsume(String dependencyCode) {
        TokenBucket b = bucketRepository.findById(dependencyCode).orElse(null);
        if (b == null || b.isFrozen() || b.getTokens() < 1) {
            return false;
        }
        b.setTokens(b.getTokens() - 1);
        b.setTotalConsumed(b.getTotalConsumed() + 1);
        bucketRepository.save(b);
        return true;
    }

    /** 冻结：停止放行与补充 */
    @Transactional
    public void freeze(String dependencyCode) {
        bucketRepository.findById(dependencyCode).ifPresent(b -> {
            if (!b.isFrozen()) {
                b.setFrozen(true);
                b.setFrozenAt(Instant.now());
                bucketRepository.save(b);
            }
        });
    }

    /** 解冻：从冻结余额继续，不重置补充时钟之外的任何账目（不补发） */
    @Transactional
    public void unfreeze(String dependencyCode) {
        bucketRepository.findById(dependencyCode).ifPresent(b -> {
            if (b.isFrozen()) {
                b.setFrozen(false);
                b.setFrozenAt(null);
                b.setLastRefillAt(Instant.now());
                bucketRepository.save(b);
            }
        });
    }

    /**
     * 容量画像生效上限 → 桶补充速率的同步：
     * 速率 ≤ 0 时冻结（不放行、不补充）；速率 > 0 时解冻并按该速率渐进补充。
     */
    @Transactional
    public void syncRate(String dependencyCode, int rate) {
        bucketRepository.findById(dependencyCode).ifPresent(b -> {
            b.setRefillPerTick(Math.max(0, rate));
            if (rate <= 0 && !b.isFrozen()) {
                b.setFrozen(true);
                b.setFrozenAt(Instant.now());
            } else if (rate > 0 && b.isFrozen()) {
                b.setFrozen(false);
                b.setFrozenAt(null);
                b.setLastRefillAt(Instant.now());
            }
            bucketRepository.save(b);
        });
    }

    /** 本周期可投放槽位快照：桶不存在或已冻结时为 0 */
    public int available(String dependencyCode) {
        TokenBucket b = bucketRepository.findById(dependencyCode).orElse(null);
        if (b == null || b.isFrozen()) {
            return 0;
        }
        return b.getTokens();
    }

    public List<TokenBucket> list() {
        return bucketRepository.findAll();
    }

    public TokenBucket get(String dependencyCode) {
        return bucketRepository.findById(dependencyCode).orElse(null);
    }
}
