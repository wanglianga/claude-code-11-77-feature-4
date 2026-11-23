package com.example.clearing.web;

import com.example.clearing.domain.CapacityEvent;
import com.example.clearing.domain.CapacityProfile;
import com.example.clearing.repo.CapacityEventRepository;
import com.example.clearing.service.CapacityProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "容量画像与降级恢复审计")
@RestController
@RequestMapping("/api/capacity")
public class CapacityController {

    private final CapacityProfileService profileService;
    private final CapacityEventRepository eventRepository;

    public CapacityController(CapacityProfileService profileService, CapacityEventRepository eventRepository) {
        this.profileService = profileService;
        this.eventRepository = eventRepository;
    }

    @Operation(summary = "容量画像库", description = "每个被保护依赖的声明上限（硬边界）、生效上限、每来源最低服务份额、"
            + "状态机、探针统计与近期成功率画像")
    @GetMapping("/profiles")
    public List<CapacityProfile> profiles() {
        return profileService.list();
    }

    @Operation(summary = "单个依赖的容量画像")
    @GetMapping("/profiles/{code}")
    public CapacityProfile profile(@PathVariable String code) {
        CapacityProfile profile = profileService.get(code);
        if (profile == null) {
            throw new IllegalArgumentException("未知依赖画像: " + code);
        }
        return profile;
    }

    @Operation(summary = "容量事件审计", description = "探测（含探测上限跳过）、降级、熔断、恢复爬坡、人工覆盖的完整轨迹")
    @GetMapping("/events")
    public List<CapacityEvent> events(@RequestParam(defaultValue = "100") int limit) {
        return profileService.events(limit);
    }

    @Operation(summary = "单个依赖的容量事件轨迹")
    @GetMapping("/events/{code}")
    public List<CapacityEvent> eventsOf(@PathVariable String code) {
        return eventRepository.findByDependencyCodeOrderByCreatedAtAsc(code);
    }
}
