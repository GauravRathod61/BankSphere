package com.banking.account_service.service;

import com.banking.account_service.exception.BalanceUpdateConflictException;
import com.banking.account_service.model.Account;
import com.banking.account_service.model.BalanceOperation;
import com.banking.account_service.repository.AccountRepository;
import com.banking.account_service.repository.BalanceOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BalanceOperationRepository balanceOperationRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        accountService = new AccountService(accountRepository, balanceOperationRepository, transactionTemplate);
    }

    @Test
    void testUpdateBalance_RetryOnOptimisticLockingFailure_Succeeds() {
        String accountNumber = "ACC1234567";
        Account account1 = new Account();
        account1.setAccountNumber(accountNumber);
        account1.setBalance(new BigDecimal("500.00"));
        account1.setAccountType(Account.AccountType.CURRENT);
        account1.setStatus(Account.AccountStatus.ACTIVE);

        Account account2 = new Account();
        account2.setAccountNumber(accountNumber);
        account2.setBalance(new BigDecimal("500.00"));
        account2.setAccountType(Account.AccountType.CURRENT);
        account2.setStatus(Account.AccountStatus.ACTIVE);

        when(accountRepository.findByAccountNumber(accountNumber))
                .thenReturn(Optional.of(account1))
                .thenReturn(Optional.of(account2));

        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, 1L))
                .thenReturn(account2);

        accountService.updateBalance(accountNumber, new BigDecimal("100.00"), "test-op-retry");

        verify(accountRepository, times(2)).findByAccountNumber(accountNumber);
        verify(accountRepository, times(2)).saveAndFlush(any(Account.class));
        assertEquals(new BigDecimal("600.00"), account2.getBalance());
    }

    @Test
    void testUpdateBalance_ExhaustedRetries_ThrowsBalanceUpdateConflictException() {
        String accountNumber = "ACC1234567";
        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setBalance(new BigDecimal("500.00"));
        account.setAccountType(Account.AccountType.CURRENT);
        account.setStatus(Account.AccountStatus.ACTIVE);

        when(accountRepository.findByAccountNumber(accountNumber))
                .thenReturn(Optional.of(account));

        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, 1L));

        assertThrows(BalanceUpdateConflictException.class, () ->
                accountService.updateBalance(accountNumber, new BigDecimal("100.00"), "test-op-conflict"));

        verify(accountRepository, times(3)).findByAccountNumber(accountNumber);
        verify(accountRepository, times(3)).saveAndFlush(any(Account.class));
    }

    @Test
    void testUpdateBalance_DuplicateOperationKey_ReturnsImmediately() {
        String accountNumber = "ACC1234567";
        String opKey = "duplicate-key-123";

        when(balanceOperationRepository.existsByOperationKey(opKey)).thenReturn(true);

        accountService.updateBalance(accountNumber, new BigDecimal("100.00"), opKey);

        verify(accountRepository, never()).findByAccountNumber(anyString());
    }

    @Test
    void testUpdateBalance_DataIntegrityViolationException_RecoversIfFound() {
        String accountNumber = "ACC1234567";
        String opKey = "race-key-123";

        doThrow(new DataIntegrityViolationException("Duplicate entry"))
                .when(transactionTemplate).executeWithoutResult(any());

        when(balanceOperationRepository.findByOperationKey(opKey))
                .thenReturn(Optional.of(new BalanceOperation()));

        assertDoesNotThrow(() -> accountService.updateBalance(accountNumber, new BigDecimal("100.00"), opKey));
    }

    @Test
    void testUpdateBalance_DataIntegrityViolationException_RethrowsIfNotFound() {
        String accountNumber = "ACC1234567";
        String opKey = "race-key-456";

        doThrow(new DataIntegrityViolationException("Other DB constraint error"))
                .when(transactionTemplate).executeWithoutResult(any());

        when(balanceOperationRepository.findByOperationKey(opKey))
                .thenReturn(Optional.empty());

        assertThrows(DataIntegrityViolationException.class, () ->
                accountService.updateBalance(accountNumber, new BigDecimal("100.00"), opKey));
    }

    @Test
    void testCompensationIdempotency_SameKeyExecutedTwice_IncreasesBalanceOnlyOnce() {
        String accountNumber = "ACC1234567";
        String compKey = "tx-123-DEBIT-COMPENSATION";
        BigDecimal initialBalance = new BigDecimal("500.00");
        BigDecimal refundAmount = new BigDecimal("100.00");

        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setBalance(initialBalance);
        account.setAccountType(Account.AccountType.CURRENT);
        account.setStatus(Account.AccountStatus.ACTIVE);

        when(accountRepository.findByAccountNumber(accountNumber)).thenReturn(Optional.of(account));
        when(accountRepository.saveAndFlush(any(Account.class))).thenReturn(account);

        // 1st execution: operationKey does not exist in DB yet
        when(balanceOperationRepository.existsByOperationKey(compKey)).thenReturn(false);
        accountService.updateBalance(accountNumber, refundAmount, compKey);

        // Verify balance increased by refund amount once (500 + 100 = 600)
        assertEquals(new BigDecimal("600.00"), account.getBalance(), "Balance must increase by refund amount on first execution");

        // 2nd execution: operationKey NOW exists in DB
        when(balanceOperationRepository.existsByOperationKey(compKey)).thenReturn(true);
        accountService.updateBalance(accountNumber, refundAmount, compKey);

        // Verify balance remains 600.00 (NOT 700.00)
        assertEquals(new BigDecimal("600.00"), account.getBalance(), "Balance must NOT increase a second time when same compensation key is executed again");

        // Verify account repository saveAndFlush was called ONLY ONCE
        verify(accountRepository, times(1)).saveAndFlush(any(Account.class));
    }
}
