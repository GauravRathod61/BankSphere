package com.banking.transaction_service.service;

import com.banking.transaction_service.client.AccountServiceClient;
import com.banking.transaction_service.dto.TransactionRequestDto;
import com.banking.transaction_service.exception.AccountServiceRejectedException;
import com.banking.transaction_service.exception.AccountServiceSecurityException;
import com.banking.transaction_service.exception.AccountServiceTimeoutException;
import com.banking.transaction_service.exception.AccountServiceUnavailableException;
import com.banking.transaction_service.model.Transaction;
import com.banking.transaction_service.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public enum OperationResult {
        APPLIED, DEFINITELY_NOT_APPLIED, AMBIGUOUS
    }

    @Autowired
    public TransactionService(TransactionRepository transactionRepository,
                              AccountServiceClient accountServiceClient,
                              PlatformTransactionManager transactionManager,
                              MeterRegistry meterRegistry) {
        this(transactionRepository, accountServiceClient, new TransactionTemplate(transactionManager), meterRegistry);
    }

    public TransactionService(TransactionRepository transactionRepository,
                              AccountServiceClient accountServiceClient,
                              PlatformTransactionManager transactionManager) {
        this(transactionRepository, accountServiceClient, new TransactionTemplate(transactionManager), new SimpleMeterRegistry());
    }

    public TransactionService(TransactionRepository transactionRepository,
                              AccountServiceClient accountServiceClient,
                              TransactionTemplate transactionTemplate) {
        this(transactionRepository, accountServiceClient, transactionTemplate, new SimpleMeterRegistry());
    }

    public TransactionService(TransactionRepository transactionRepository,
                              AccountServiceClient accountServiceClient,
                              TransactionTemplate transactionTemplate,
                              MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.accountServiceClient = accountServiceClient;
        this.transactionTemplate = transactionTemplate;
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
    }

    public Transaction processTransaction(TransactionRequestDto dto) {
        return processTransaction(dto, null);
    }

    public Transaction processTransaction(TransactionRequestDto dto, String idempotencyKey) {
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean hasKey = idempotencyKey != null && !idempotencyKey.isBlank();

        if (hasKey) {
            Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent hit for transactionId={}", existing.get().getTransactionId());
                return existing.get();
            }
        }

        Transaction transaction = new Transaction();
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setSourceAccountNumber(dto.getSourceAccountNumber());
        transaction.setTargetAccountNumber(dto.getTargetAccountNumber());
        transaction.setAmount(dto.getAmount());
        transaction.setType(dto.getType());
        transaction.setDescription(dto.getDescription());
        transaction.setStatus(Transaction.TransactionStatus.PENDING);

        if (hasKey) {
            transaction.setIdempotencyKey(idempotencyKey);
        }

        log.info("Processing transactionId={}, type={}, sourceAccount={}, targetAccount={}",
                transaction.getTransactionId(), transaction.getType(),
                maskAccountNumber(dto.getSourceAccountNumber()),
                maskAccountNumber(dto.getTargetAccountNumber()));

        try {
            transaction = saveInitialTransaction(transaction);
        } catch (DataIntegrityViolationException ex) {
            if (hasKey) {
                Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    log.info("Concurrent idempotent hit for transactionId={}", existing.get().getTransactionId());
                    return existing.get();
                }
            }
            throw ex;
        }

        Transaction finalTx;
        if (dto.getType() == Transaction.TransactionType.DEPOSIT || dto.getType() == Transaction.TransactionType.WITHDRAW) {
            String opKey = dto.getType() == Transaction.TransactionType.DEPOSIT ?
                    (transaction.getTransactionId() + "-DEPOSIT") :
                    (transaction.getTransactionId() + "-WITHDRAW");
            BigDecimal signedAmount = dto.getType() == Transaction.TransactionType.DEPOSIT ?
                    dto.getAmount() : dto.getAmount().negate();

            OperationResult res = executeOrReconcileBalanceUpdate(dto.getSourceAccountNumber(), signedAmount, opKey);

            if (res == OperationResult.APPLIED) {
                transaction.setStatus(Transaction.TransactionStatus.SUCCESS);
            } else if (res == OperationResult.DEFINITELY_NOT_APPLIED) {
                transaction.setStatus(Transaction.TransactionStatus.FAILED);
            } else { // AMBIGUOUS
                transaction.setStatus(Transaction.TransactionStatus.FAILED_NEEDS_MANUAL_REVIEW);
            }
            finalTx = transactionRepository.save(transaction);
        } else {
            // TRANSFER Saga Flow
            String debitKey = transaction.getTransactionId() + "-DEBIT";
            OperationResult debitRes = executeOrReconcileBalanceUpdate(dto.getSourceAccountNumber(), dto.getAmount().negate(), debitKey);

            if (debitRes == OperationResult.DEFINITELY_NOT_APPLIED) {
                transaction.setStatus(Transaction.TransactionStatus.FAILED);
                finalTx = transactionRepository.save(transaction);
            } else if (debitRes == OperationResult.AMBIGUOUS) {
                transaction.setStatus(Transaction.TransactionStatus.FAILED_NEEDS_MANUAL_REVIEW);
                finalTx = transactionRepository.save(transaction);
            } else {
                // Debit is APPLIED -> proceed to Credit
                String creditKey = transaction.getTransactionId() + "-CREDIT";
                OperationResult creditRes = executeOrReconcileBalanceUpdate(dto.getTargetAccountNumber(), dto.getAmount(), creditKey);

                if (creditRes == OperationResult.APPLIED) {
                    transaction.setStatus(Transaction.TransactionStatus.SUCCESS);
                    finalTx = transactionRepository.save(transaction);
                } else if (creditRes == OperationResult.AMBIGUOUS) {
                    // Double ambiguity on credit side: NO compensation, debit retained
                    transaction.setStatus(Transaction.TransactionStatus.FAILED_NEEDS_MANUAL_REVIEW);
                    finalTx = transactionRepository.save(transaction);
                } else {
                    // creditRes == DEFINITELY_NOT_APPLIED -> Must attempt compensation
                    log.warn("Triggering compensation refund for transactionId={}", transaction.getTransactionId());
                    String compKey = transaction.getTransactionId() + "-DEBIT-COMPENSATION";
                    OperationResult compRes = executeOrReconcileBalanceUpdate(dto.getSourceAccountNumber(), dto.getAmount(), compKey);

                    Transaction compTx = new Transaction();
                    compTx.setTransactionId(UUID.randomUUID().toString());
                    compTx.setSourceAccountNumber(dto.getSourceAccountNumber());
                    compTx.setTargetAccountNumber(dto.getTargetAccountNumber());
                    compTx.setAmount(dto.getAmount());
                    compTx.setType(Transaction.TransactionType.TRANSFER);
                    compTx.setDescription("Compensation for transaction " + transaction.getTransactionId());
                    compTx.setStatus(compRes == OperationResult.APPLIED ? Transaction.TransactionStatus.SUCCESS : Transaction.TransactionStatus.FAILED);
                    compTx = saveInitialTransaction(compTx);

                    transaction.setCompensationTransactionId(compTx.getTransactionId());

                    if (compRes == OperationResult.APPLIED) {
                        transaction.setStatus(Transaction.TransactionStatus.REVERSED);
                    } else { // DEFINITELY_NOT_APPLIED or AMBIGUOUS
                        transaction.setStatus(Transaction.TransactionStatus.FAILED_NEEDS_MANUAL_REVIEW);
                    }

                    finalTx = transactionRepository.save(transaction);
                }
            }
        }

        recordMetrics(sample, finalTx);
        log.info("Transaction completed: transactionId={}, status={}", finalTx.getTransactionId(), finalTx.getStatus());
        return finalTx;
    }

    private void recordMetrics(Timer.Sample sample, Transaction tx) {
        String typeStr = tx.getType() != null ? tx.getType().name() : "UNKNOWN";
        sample.stop(meterRegistry.timer("banking.transactions.duration", "type", typeStr));

        String statusStr;
        if (tx.getStatus() == Transaction.TransactionStatus.SUCCESS) {
            statusStr = "SUCCESS";
        } else if (tx.getStatus() == Transaction.TransactionStatus.REVERSED) {
            statusStr = "REVERSED";
        } else if (tx.getStatus() == Transaction.TransactionStatus.FAILED_NEEDS_MANUAL_REVIEW) {
            statusStr = "MANUAL_REVIEW";
        } else {
            statusStr = "FAILED";
        }
        meterRegistry.counter("banking.transactions.processed", "type", typeStr, "status", statusStr).increment();
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null) return "null";
        if (accountNumber.length() <= 4) return "****";
        return accountNumber.substring(0, 2) + "***" + accountNumber.substring(accountNumber.length() - 2);
    }

    private OperationResult executeOrReconcileBalanceUpdate(String accountNumber, BigDecimal amount, String operationKey) {
        try {
            accountServiceClient.updateAccountBalance(accountNumber, amount, operationKey);
            return OperationResult.APPLIED;
        } catch (AccountServiceRejectedException | AccountServiceSecurityException | AccountServiceUnavailableException e) {
            return OperationResult.DEFINITELY_NOT_APPLIED;
        } catch (AccountServiceTimeoutException e) {
            // Ambiguous read timeout -> perform SAME-KEY RECONCILIATION call
            try {
                accountServiceClient.updateAccountBalance(accountNumber, amount, operationKey);
                return OperationResult.APPLIED;
            } catch (AccountServiceRejectedException | AccountServiceSecurityException | AccountServiceUnavailableException e2) {
                return OperationResult.DEFINITELY_NOT_APPLIED;
            } catch (Exception e2) {
                return OperationResult.AMBIGUOUS;
            }
        }
    }

    private Transaction saveInitialTransaction(Transaction transaction) {
        return transactionTemplate.execute(status -> transactionRepository.saveAndFlush(transaction));
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
}
