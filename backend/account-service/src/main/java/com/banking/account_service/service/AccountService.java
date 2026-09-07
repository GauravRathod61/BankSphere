package com.banking.account_service.service;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.exception.BalanceUpdateConflictException;
import com.banking.account_service.model.Account;
import com.banking.account_service.model.BalanceOperation;
import com.banking.account_service.repository.AccountRepository;
import com.banking.account_service.repository.BalanceOperationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final BalanceOperationRepository balanceOperationRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    @Autowired
    public AccountService(AccountRepository accountRepository,
                          BalanceOperationRepository balanceOperationRepository,
                          PlatformTransactionManager transactionManager,
                          MeterRegistry meterRegistry) {
        this(accountRepository, balanceOperationRepository, new TransactionTemplate(transactionManager), meterRegistry);
    }

    public AccountService(AccountRepository accountRepository,
                          BalanceOperationRepository balanceOperationRepository,
                          TransactionTemplate transactionTemplate) {
        this(accountRepository, balanceOperationRepository, transactionTemplate, new SimpleMeterRegistry());
    }

    public AccountService(AccountRepository accountRepository,
                          BalanceOperationRepository balanceOperationRepository,
                          TransactionTemplate transactionTemplate,
                          MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.balanceOperationRepository = balanceOperationRepository;
        this.transactionTemplate = transactionTemplate;
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
    }

    @Transactional
    public Account createAccount(CreateAccountDto createAccountDto) {
        Account account = new Account();
        account.setCustomerId(createAccountDto.getCustomerId());
        account.setAccountType(createAccountDto.getAccountType());
        account.setAccountNumber(generateAccountNumber());
        Account saved = accountRepository.save(account);

        meterRegistry.counter("banking.accounts.created", "type", saved.getAccountType().name()).increment();
        log.info("Created new account type={} for customerId={}", saved.getAccountType(), saved.getCustomerId());
        return saved;
    }

    public Account getAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));
    }

    public List<Account> getCustomerAccounts(Long customerId) {
        return accountRepository.findByCustomerId(customerId);
    }

    public void updateBalance(String accountNumber, BigDecimal amount, String operationKey) {
        if (operationKey != null && !operationKey.isBlank() && balanceOperationRepository.existsByOperationKey(operationKey)) {
            meterRegistry.counter("banking.account.balance.updates", "status", "SUCCESS").increment();
            log.info("Duplicate operation processed as idempotent success for account={}", maskAccountNumber(accountNumber));
            return;
        }

        int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Account account = getAccount(accountNumber);
                    
                    if (account.getStatus() == Account.AccountStatus.FROZEN) {
                        throw new RuntimeException("Account is frozen");
                    }

                    BigDecimal newBalance = account.getBalance().add(amount);
                    
                    // Minimum Balance Rule for Savings
                    if (account.getAccountType() == Account.AccountType.SAVINGS && newBalance.compareTo(new BigDecimal("100")) < 0) {
                        throw new RuntimeException("Minimum balance rule violated for SAVINGS account");
                    }
                    
                    if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                        throw new RuntimeException("Insufficient funds");
                    }
                    
                    account.setBalance(newBalance);
                    accountRepository.saveAndFlush(account);

                    if (operationKey != null && !operationKey.isBlank()) {
                        BalanceOperation op = new BalanceOperation();
                        op.setOperationKey(operationKey);
                        op.setAccountNumber(accountNumber);
                        op.setAmount(amount);
                        balanceOperationRepository.saveAndFlush(op);
                    }
                });
                meterRegistry.counter("banking.account.balance.updates", "status", "SUCCESS").increment();
                log.info("Balance updated successfully for account={}: delta={}", maskAccountNumber(accountNumber), amount);
                return;
            } catch (DataIntegrityViolationException ex) {
                if (operationKey != null && !operationKey.isBlank()) {
                    Optional<BalanceOperation> existingOp = balanceOperationRepository.findByOperationKey(operationKey);
                    if (existingOp.isPresent()) {
                        meterRegistry.counter("banking.account.balance.updates", "status", "SUCCESS").increment();
                        log.info("Concurrent duplicate operation processed for account={}", maskAccountNumber(accountNumber));
                        return; // Concurrent insert processed successfully
                    }
                }
                meterRegistry.counter("banking.account.balance.updates", "status", "FAILED").increment();
                throw ex; // Rethrow original exception if not caused by duplicate operationKey
            } catch (org.springframework.dao.OptimisticLockingFailureException | jakarta.persistence.OptimisticLockException ex) {
                if (attempt == maxAttempts) {
                    meterRegistry.counter("banking.account.balance.updates", "status", "CONFLICT").increment();
                    log.error("Optimistic lock conflict updating balance for account={} after {} attempts", maskAccountNumber(accountNumber), maxAttempts);
                    throw new BalanceUpdateConflictException(
                            "Failed to update balance for account " + accountNumber + " after " + maxAttempts + " attempts due to concurrent updates", ex);
                }
                try {
                    long minMs = attempt == 1 ? 50 : 150;
                    long maxMs = attempt == 1 ? 250 : 500;
                    long backoffMs = java.util.concurrent.ThreadLocalRandom.current().nextLong(minMs, maxMs);
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Update balance interrupted during backoff", ie);
                }
            } catch (RuntimeException ex) {
                meterRegistry.counter("banking.account.balance.updates", "status", "FAILED").increment();
                log.warn("Balance update rejected for account={}: {}", maskAccountNumber(accountNumber), ex.getMessage());
                throw ex;
            }
        }
    }

    @Transactional
    public void freezeAccount(String accountNumber) {
        Account account = getAccount(accountNumber);
        account.setStatus(Account.AccountStatus.FROZEN);
        accountRepository.save(account);
        log.info("Account frozen: {}", maskAccountNumber(accountNumber));
    }

    @Transactional
    public void unfreezeAccount(String accountNumber) {
        Account account = getAccount(accountNumber);
        account.setStatus(Account.AccountStatus.ACTIVE);
        accountRepository.save(account);
        log.info("Account unfrozen: {}", maskAccountNumber(accountNumber));
    }

    private String generateAccountNumber() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 10).toUpperCase();
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "***";
        }
        return accountNumber.substring(0, 2) + "***" + accountNumber.substring(accountNumber.length() - 2);
    }
}
