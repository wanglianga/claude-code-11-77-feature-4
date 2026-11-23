package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.clearing.service.DemoScenarioService;
import com.example.clearing.service.TokenBucketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 令牌桶不变量：冻结不补充不放行；解冻不补发，按剩余预算渐进补充 */
@SpringBootTest
class TokenBucketServiceTest {

    @Autowired
    private TokenBucketService buckets;

    @Autowired
    private DemoScenarioService demo;

    @BeforeEach
    void reset() {
        demo.reset();
    }

    @Test
    void freezeStopsRefillAndRelease_unfreezeDoesNotBackfill() {
        // 消耗 4 个令牌，余额 2
        for (int i = 0; i < 4; i++) {
            assertTrue(buckets.tryConsume("RULE_ENGINE"));
        }
        assertEquals(2, buckets.get("RULE_ENGINE").getTokens());

        buckets.freeze("RULE_ENGINE");
        buckets.refillAll();
        buckets.refillAll();
        assertEquals(2, buckets.get("RULE_ENGINE").getTokens(), "冻结期间不补充");
        assertEquals(0, buckets.get("RULE_ENGINE").getTotalRefilled(), "冻结期间累计补充为 0");
        assertFalse(buckets.tryConsume("RULE_ENGINE"), "冻结期间不放行");

        buckets.unfreeze("RULE_ENGINE");
        buckets.refillAll();
        // 只补充一个周期（+2），不补发冻结期间错过的 2 个周期
        assertEquals(4, buckets.get("RULE_ENGINE").getTokens(), "解冻后仅渐进补充，不补发");
        assertEquals(2, buckets.get("RULE_ENGINE").getTotalRefilled(), "累计补充仅为解冻后的 1 个周期");
    }

    @Test
    void consumeRespectsBalance() {
        assertTrue(buckets.tryConsume("FUND_GW"));
        assertTrue(buckets.tryConsume("FUND_GW"));
        assertTrue(buckets.tryConsume("FUND_GW"));
        assertFalse(buckets.tryConsume("FUND_GW"), "容量 3 耗尽后不再放行");
        assertEquals(3, buckets.get("FUND_GW").getTotalConsumed());
    }
}
