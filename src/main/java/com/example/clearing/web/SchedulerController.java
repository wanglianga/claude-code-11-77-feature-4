package com.example.clearing.web;

import com.example.clearing.dto.TickResult;
import com.example.clearing.service.ClearingScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "调度器")
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final ClearingScheduler scheduler;

    public SchedulerController(ClearingScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Operation(summary = "手动执行一个调度周期",
            description = "健康探针（探测上限内）→ 降级编排（局部限流）→ 恢复节流（保守爬坡）→ 截止风险刷新 → "
                    + "本地桶补充 → 队列配额计划（每来源最低份额 + 风险序公共池）→ 投放 → 配额证据 → 风暴观测")
    @PostMapping("/tick")
    public TickResult tick() {
        return scheduler.tick();
    }
}
