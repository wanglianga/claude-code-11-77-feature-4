package com.example.clearing.repo;

import com.example.clearing.domain.EntryStatus;
import com.example.clearing.domain.EntryType;
import com.example.clearing.domain.LedgerEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Optional<LedgerEntry> findByOperationNo(String operationNo);

    Optional<LedgerEntry> findByBusinessKeyAndBillVersionAndEntryType(String businessKey, String billVersion,
            EntryType entryType);

    List<LedgerEntry> findByStatus(EntryStatus status);

    List<LedgerEntry> findByBusinessKey(String businessKey);

    List<LedgerEntry> findByTaskId(Long taskId);
}
