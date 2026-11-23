package com.example.clearing.web;

import com.example.clearing.dto.FundSimRequest;
import com.example.clearing.dto.RuleSimRequest;
import com.example.clearing.service.DownstreamSimulator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "下游模拟器")
@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final DownstreamSimulator simulator;

    public SimulationController(DownstreamSimulator simulator) {
        this.simulator = simulator;
    }

    @Operation(summary = "模拟器当前状态")
    @GetMapping
    public Map<String, Object> state() {
        return Map.of(
                "ruleEngineMode", simulator.getRuleMode(),
                "fundGatewayMode", simulator.getFundMode(),
                "fundDelaySeconds", simulator.getFundDelaySeconds());
    }

    @Operation(summary = "设置待遇规则服务调用模式", description = "NORMAL 正常 / TIMEOUT 调用超时（瞬时故障）")
    @PostMapping("/rule-engine")
    public Map<String, Object> setRuleMode(@Valid @RequestBody RuleSimRequest request) {
        simulator.setRuleMode(request.mode());
        return state();
    }

    @Operation(summary = "设置基金网关模式", description = "NORMAL 即时回执 / DELAYED 受理成功但回执延迟 / DOWN 连接失败")
    @PostMapping("/fund-gateway")
    public Map<String, Object> setFundMode(@Valid @RequestBody FundSimRequest request) {
        simulator.setFundMode(request.mode());
        if (request.delaySeconds() != null) {
            simulator.setFundDelaySeconds(request.delaySeconds());
        }
        return state();
    }
}
