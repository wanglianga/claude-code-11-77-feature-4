package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.EntryStatus;
import com.example.clearing.domain.EntryType;
import com.example.clearing.domain.LedgerEntry;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等账本：以清单版本 + 清算操作号约束入账与回传。
 *  - 扣款：操作号 CD-{业务键}-{版本}，唯一约束保证同一清单只入账一次；
 *  - 回传：操作号 FR-{业务键}-{版本}，回执延迟期间保持 PENDING_CONFIRM，
 *    只按操作号查询回执，绝不新建扣款分录。
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerRepository;
    private final ClearingTaskRepository taskRepository;
    private final EventLog eventLog;

    public LedgerService(LedgerEntryRepository ledgerRepository,
            ClearingTaskRepository taskRepository,
            EventLog eventLog) {
        this.ledgerRepository = ledgerRepository;
        this.taskRepository = taskRepository;
        this.eventLog = eventLog;
    }

    public static String debitOpNo(ClearingTask task) {
        return "CD-" + task.getBusinessKey() + "-" + task.getBillVersion();
    }

    public static String fundReturnOpNo(ClearingTask task) {
        return "FR-" + task.getBusinessKey() + "-" + task.getBillVersion();
    }

    /** 幂等扣款：已存在同操作号分录则直接返回（重复记账被唯一约束吸收） */
    @Transactional(propagation = Propagation.REQUIRED)
    public LedgerEntry postDebit(ClearingTask task, BigDecimal amount) {
        String opNo = debitOpNo(task);
        Optional<LedgerEntry> existing = ledgerRepository.findByOperationNo(opNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        LedgerEntry entry = new LedgerEntry();
        entry.setOperationNo(opNo);
        entry.setBusinessKey(task.getBusinessKey());
        entry.setBillVersion(task.getBillVersion());
        entry.setEntryType(EntryType.CLEAR_DEBIT);
        entry.setAmount(amount);
        entry.setStatus(EntryStatus.POSTED);
        entry.setTaskId(task.getId());
        try {
            return ledgerRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            return ledgerRepository.findByOperationNo(opNo).orElseThrow(() -> e);
        }
    }

    /** 幂等回传分录：已存在则返回原分录（保持待确认，不新建） */
    @Transactional(propagation = Propagation.REQUIRED)
    public LedgerEntry postOrGetFundReturn(ClearingTask task, BigDecimal amount) {
        String opNo = fundReturnOpNo(task);
        Optional<LedgerEntry> existing = ledgerRepository.findByOperationNo(opNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        LedgerEntry entry = new LedgerEntry();
        entry.setOperationNo(opNo);
        entry.setBusinessKey(task.getBusinessKey());
        entry.setBillVersion(task.getBillVersion());
        entry.setEntryType(EntryType.FUND_RETURN);
        entry.setAmount(amount);
        entry.setStatus(EntryStatus.PENDING_CONFIRM);
        entry.setTaskId(task.getId());
        try {
            return ledgerRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            return ledgerRepository.findByOperationNo(opNo).orElseThrow(() -> e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public LedgerEntry save(LedgerEntry entry) {
        return ledgerRepository.save(entry);
    }

    /** 回执确认：分录 CONFIRMED，任务进入终态 */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean confirm(String operationNo) {
        Optional<LedgerEntry> opt = ledgerRepository.findByOperationNo(operationNo);
        if (opt.isEmpty()) {
            return false;
        }
        LedgerEntry entry = opt.get();
        if (entry.getStatus() == EntryStatus.CONFIRMED) {
            return true;
        }
        entry.setStatus(EntryStatus.CONFIRMED);
        entry.setConfirmedAt(Instant.now());
        ledgerRepository.save(entry);
        taskRepository.findById(entry.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatus.CONFIRMED);
            task.setStage(com.example.clearing.domain.Stage.DONE);
            eventLog.record(task, Decision.CONFIRMED,
                    "回执已确认（操作号 " + operationNo + "），清算完成，金额 " + entry.getAmount());
            taskRepository.save(task);
        });
        return true;
    }

    public Optional<LedgerEntry> byOperationNo(String operationNo) {
        return ledgerRepository.findByOperationNo(operationNo);
    }

    public Optional<LedgerEntry> fundReturnOf(String businessKey, String billVersion) {
        return ledgerRepository.findByBusinessKeyAndBillVersionAndEntryType(businessKey, billVersion,
                EntryType.FUND_RETURN);
    }

    public List<LedgerEntry> pendingConfirm() {
        return ledgerRepository.findByStatus(EntryStatus.PENDING_CONFIRM);
    }

    public List<LedgerEntry> all() {
        return ledgerRepository.findAll();
    }
}
