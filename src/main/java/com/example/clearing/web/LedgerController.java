package com.example.clearing.web;

import com.example.clearing.domain.EntryStatus;
import com.example.clearing.domain.LedgerEntry;
import com.example.clearing.repo.LedgerEntryRepository;
import com.example.clearing.service.DownstreamSimulator;
import com.example.clearing.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "幂等账本")
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledgerService;
    private final LedgerEntryRepository ledgerRepository;
    private final DownstreamSimulator simulator;

    public LedgerController(LedgerService ledgerService, LedgerEntryRepository ledgerRepository,
            DownstreamSimulator simulator) {
        this.ledgerService = ledgerService;
        this.ledgerRepository = ledgerRepository;
        this.simulator = simulator;
    }

    @Operation(summary = "账本分录查询")
    @GetMapping
    public List<LedgerEntry> list(@RequestParam(required = false) EntryStatus status,
            @RequestParam(required = false) String businessKey) {
        if (status != null) {
            return ledgerRepository.findByStatus(status);
        }
        if (businessKey != null) {
            return ledgerRepository.findByBusinessKey(businessKey);
        }
        return ledgerRepository.findAll();
    }

    @Operation(summary = "按清算操作号查询分录", description = "回执延迟期间按操作号查询待确认分录")
    @GetMapping("/{operationNo}")
    public LedgerEntry byOperationNo(@PathVariable String operationNo) {
        return ledgerService.byOperationNo(operationNo)
                .orElseThrow(() -> new IllegalArgumentException("分录不存在: " + operationNo));
    }

    @Operation(summary = "确认回执", description = "模拟基金回执到达/人工对账后补确认，分录与关联任务转为 CONFIRMED")
    @PostMapping("/{operationNo}/confirm")
    public LedgerEntry confirm(@PathVariable String operationNo) {
        simulator.confirmManually(operationNo);
        if (!ledgerService.confirm(operationNo)) {
            throw new IllegalArgumentException("分录不存在: " + operationNo);
        }
        return ledgerService.byOperationNo(operationNo).orElseThrow();
    }
}
