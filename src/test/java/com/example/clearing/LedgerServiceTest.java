package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.EntryStatus;
import com.example.clearing.domain.LedgerEntry;
import com.example.clearing.domain.SourceType;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.LedgerEntryRepository;
import com.example.clearing.service.DemoScenarioService;
import com.example.clearing.service.LedgerService;
import com.example.clearing.service.TaskRegistrar;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 幂等账本不变量：清单版本+操作号约束入账与回传；待确认只查不扣 */
@SpringBootTest
class LedgerServiceTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private TaskRegistrar registrar;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @Autowired
    private ClearingTaskRepository taskRepository;

    @Autowired
    private DemoScenarioService demo;

    @BeforeEach
    void reset() {
        demo.reset();
    }

    private ClearingTask newTask(String key) {
        return registrar.register("B1", LocalDate.of(2026, 11, 23), key, "v1",
                SourceType.HOSPITAL, new BigDecimal("100"),
                "{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":1,\"price\":10}]}").task();
    }

    @Test
    void debitPostingIsIdempotentByOperationNo() {
        ClearingTask task = newTask("K-DEBIT");
        LedgerEntry first = ledger.postDebit(task, new BigDecimal("70.00"));
        LedgerEntry second = ledger.postDebit(task, new BigDecimal("70.00"));
        assertEquals(first.getId(), second.getId(), "同操作号重复入账返回原分录");
        assertEquals(1, ledgerRepository.findAll().size(), "同一清单只入账一次");
        assertEquals(EntryStatus.POSTED, first.getStatus());
    }

    @Test
    void fundReturnStaysPendingUntilReceiptConfirmed() {
        ClearingTask task = newTask("K-FUND");
        LedgerEntry entry = ledger.postOrGetFundReturn(task, new BigDecimal("70.00"));
        LedgerEntry again = ledger.postOrGetFundReturn(task, new BigDecimal("70.00"));
        assertEquals(entry.getId(), again.getId(), "回传分录幂等");
        assertEquals(EntryStatus.PENDING_CONFIRM, again.getStatus(), "回执延迟期间保持待确认");
        assertEquals(1, ledgerRepository.findAll().size());

        ledger.confirm(entry.getOperationNo());
        assertEquals(EntryStatus.CONFIRMED,
                ledger.byOperationNo(entry.getOperationNo()).orElseThrow().getStatus());
        assertEquals(TaskStatus.CONFIRMED,
                taskRepository.findById(task.getId()).orElseThrow().getStatus(), "确认后任务进入终态");
    }
}
