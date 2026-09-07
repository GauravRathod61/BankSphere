package com.banking.account_service.service;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.exception.BalanceUpdateConflictException;
import com.banking.account_service.model.Account;
import com.banking.account_service.model.BalanceOperation;
import com.banking.account_service.repository.AccountRepository;
import com.banking.account_service.repository.BalanceOperationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final BalanceOperationRepository balanceOperationRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public AccountService(AccountRepository accountRepository, BalanceOperationRepository balanceOperationRepository, PlatformTransactionManager transactionManager) {
        this(accountRepository, balanceOperationRepository, new TransactionTemplate(transactionManager));
    }

    public AccountService(AccountRepository accountRepository, BalanceOperationRepository balanceOperationRepository, TransactionTemplate transactionTemplate) {
        this.accountRepository = accountRepository;
        this.balanceOperationRepository = balanceOperationRepository;
        this.transactionTemplate = transactionTemplate;
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public Account createAccount(CreateAccountDto createAccountDto) {
        // Assume Customer API is verified via Gateway/API call in a real scenario
        Account account = new Account();
        account.setCustomerId(createAccountDto.getCustomerId());
        account.setAccountType(createAccountDto.getAccountType());
        account.setAccountNumber(generateAccountNumber());
        return accountRepository.save(account);
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
                return;
            } catch (DataIntegrityViolationException ex) {
                if (operationKey != null && !operationKey.isBlank()) {
                    Optional<BalanceOperation> existingOp = balanceOperationRepository.findByOperationKey(operationKey);
                    if (existingOp.isPresent()) {
                        return; // Concurrent insert processed successfully
                    }
                }
                throw ex; // Rethrow original exception if not caused by duplicate operationKey
            } catch (org.springframework.dao.OptimisticLockingFailureException | jakarta.persistence.OptimisticLockException ex) {
                if (attempt == maxAttempts) {
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
            }
        }
    }

    @Transactional
    public void freezeAccount(String accountNumber) {
        Account account = getAccount(accountNumber);
        account.setStatus(Account.AccountStatus.FROZEN);
        accountRepository.save(account);
    }

    @Transactional
    public void unfreezeAccount(String accountNumber) {
        Account account = getAccount(accountNumber);
        account.setStatus(Account.AccountStatus.ACTIVE);
        accountRepository.save(account);
    }

    private String generateAccountNumber() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 10).toUpperCase();
    }
}
