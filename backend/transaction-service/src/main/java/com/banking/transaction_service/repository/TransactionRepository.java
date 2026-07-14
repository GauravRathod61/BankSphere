package com.banking.transaction_service.repository;

import com.banking.transaction_service.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Page<Transaction> findBySourceAccountNumberOrTargetAccountNumberOrderByTimestampDesc(
            String sourceAccountNumber, String targetAccountNumber, Pageable pageable);
            
    List<Transaction> findBySourceAccountNumber(String sourceAccountNumber);

    List<Transaction> findTop10BySourceAccountNumberOrTargetAccountNumberOrderByTimestampDesc(
            String sourceAccountNumber, String targetAccountNumber);

    List<Transaction> findBySourceAccountNumberAndTimestampBetweenOrTargetAccountNumberAndTimestampBetweenOrderByTimestampDesc(
            String source1, java.time.LocalDateTime start1, java.time.LocalDateTime end1,
            String target2, java.time.LocalDateTime start2, java.time.LocalDateTime end2);
}
