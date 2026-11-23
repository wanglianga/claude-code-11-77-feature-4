package com.example.clearing.service;

import com.example.clearing.config.ClearingProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动调度开关：默认关闭（验收通过 POST /api/scheduler/tick 手动驱动，保证确定性），
 * 设置 CLEARING_SCHEDULER_AUTO=true 后按固定间隔自动调度。
 */
@Component
public class AutoScheduler {

    private final ClearingScheduler clearingScheduler;
    private final ClearingProperties properties;

    public AutoScheduler(ClearingScheduler clearingScheduler, ClearingProperties properties) {
        this.clearingScheduler = clearingScheduler;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${clearing.scheduler.interval-ms:3000}",
            initialDelayString = "${clearing.scheduler.interval-ms:3000}")
    public void autoTick() {
        if (properties.getScheduler().isAutoEnabled()) {
            clearingScheduler.tick();
        }
    }
}
