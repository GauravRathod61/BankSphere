package com.banking.transaction_service.service;

import com.banking.transaction_service.client.AccountServiceClient;
import com.banking.transaction_service.dto.TransactionRequestDto;
import com.banking.transaction_service.model.Transaction;
import com.banking.transaction_service.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;

    public TransactionService(TransactionRepository transactionRepository, AccountServiceClient accountServiceClient) {
        this.transactionRepository = transactionRepository;
        this.accountServiceClient = accountServiceClient;
    }

    @Transactional
    public Transaction processTransaction(TransactionRequestDto dto) {
        Transaction transaction = new Transaction();
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setSourceAccountNumber(dto.getSourceAccountNumber());
        transaction.setTargetAccountNumber(dto.getTargetAccountNumber());
        transaction.setAmount(dto.getAmount());
        transaction.setType(dto.getType());
        transaction.setDescription(dto.getDescription());
        transaction.setStatus(Transaction.TransactionStatus.PENDING);
        
        transaction = transactionRepository.save(transaction);

        try {
            switch (dto.getType()) {
                case DEPOSIT:
                    updateAccountBalance(dto.getSourceAccountNumber(), dto.getAmount());
                    break;
                case WITHDRAW:
                    updateAccountBalance(dto.getSourceAccountNumber(), dto.getAmount().negate());
                    break;
                case TRANSFER:
                    // Perform transfer in a distributed system needs Saga or 2PC.
                    // For simplicity, we assume account service handles synchronous updates correctly.
                    updateAccountBalance(dto.getSourceAccountNumber(), dto.getAmount().negate());
                    updateAccountBalance(dto.getTargetAccountNumber(), dto.getAmount());
                    break;
            }
            transaction.setStatus(Transaction.TransactionStatus.SUCCESS);
        } catch (Exception e) {
            transaction.setStatus(Transaction.TransactionStatus.FAILED);
            // In a real-world scenario we'd do compensating transactions (rollback) for TRANSFER
            // if the target account update fails.
            throw e;
        }

        return transactionRepository.save(transaction);
    }

    public Page<Transaction> getTransactionHistory(String accountNumber, int page, int size) {
        return transactionRepository.findBySourceAccountNumberOrTargetAccountNumberOrderByTimestampDesc(
                accountNumber, accountNumber, PageRequest.of(page, size));
    }

    public java.util.List<Transaction> getMiniStatement(String accountNumber) {
        return transactionRepository.findTop10BySourceAccountNumberOrTargetAccountNumberOrderByTimestampDesc(accountNumber, accountNumber);
    }

    public java.util.List<Transaction> getMonthlyStatement(String accountNumber, int year, int month) {
        java.time.YearMonth yearMonth = java.time.YearMonth.of(year, month);
        java.time.LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
        java.time.LocalDateTime end = yearMonth.atEndOfMonth().atTime(23, 59, 59, 999999999);
        return transactionRepository.findBySourceAccountNumberAndTimestampBetweenOrTargetAccountNumberAndTimestampBetweenOrderByTimestampDesc(
                accountNumber, start, end, accountNumber, start, end);
    }

    private void updateAccountBalance(String accountNumber, BigDecimal amount) {
        accountServiceClient.updateAccountBalance(accountNumber, amount);
    }
}
