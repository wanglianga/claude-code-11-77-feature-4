package com.example.clearing.web;

import com.example.clearing.domain.StormReport;
import com.example.clearing.service.StormObserver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "风暴观测")
@RestController
@RequestMapping("/api/storm")
public class StormController {

    private final StormObserver stormObserver;

    public StormController(StormObserver stormObserver) {
        this.stormObserver = stormObserver;
    }

    @Operation(summary = "各依赖最新风暴观测", description = "队列增长、令牌消耗、同依赖失败聚集")
    @GetMapping("/latest")
    public List<StormReport> latest() {
        return stormObserver.latest();
    }

    @Operation(summary = "风暴观测历史")
    @GetMapping("/reports")
    public List<StormReport> reports(@RequestParam(defaultValue = "50") int limit) {
        return stormObserver.recent(Math.min(limit, 200));
    }
}
