package com.banking.account_service.service;

import com.banking.account_service.exception.BalanceUpdateConflictException;
import com.banking.account_service.model.Account;
import com.banking.account_service.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private TransactionTemplate transactionTemplate;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        accountService = new AccountService(accountRepository, transactionTemplate);
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

        accountService.updateBalance(accountNumber, new BigDecimal("100.00"));

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
                accountService.updateBalance(accountNumber, new BigDecimal("100.00")));

        verify(accountRepository, times(3)).findByAccountNumber(accountNumber);
        verify(accountRepository, times(3)).saveAndFlush(any(Account.class));
    }
}
