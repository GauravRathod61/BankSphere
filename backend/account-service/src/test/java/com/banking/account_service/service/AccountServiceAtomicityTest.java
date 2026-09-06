package com.banking.account_service.service;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.model.Account;
import com.banking.account_service.model.BalanceOperation;
import com.banking.account_service.repository.AccountRepository;
import com.banking.account_service.repository.BalanceOperationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class AccountServiceAtomicityTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoSpyBean
    private BalanceOperationRepository balanceOperationRepository;

    @Autowired
    private EntityManager entityManager;

    private String testAccountNumber;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        balanceOperationRepository.deleteAll();

        CreateAccountDto dto = new CreateAccountDto();
        dto.setCustomerId(200L);
        dto.setAccountType(Account.AccountType.CURRENT);

        Account account = accountService.createAccount(dto);
        testAccountNumber = account.getAccountNumber();
        accountService.updateBalance(testAccountNumber, new BigDecimal("1000.00"), "atomicity-init-op");
    }

    @Test
    void testAtomicBalanceUpdateAndOperationPersistence_FailureRollsBackBalance() {
        BigDecimal initialBalance = new BigDecimal("1000.00");
        BigDecimal updateAmount = new BigDecimal("200.00");
        String opKey = "atomicity-fail-key";

        // Force BalanceOperation persistence to fail AFTER balance has been mutated in memory
        doThrow(new RuntimeException("Simulated BalanceOperation persistence failure"))
                .when(balanceOperationRepository).saveAndFlush(any(BalanceOperation.class));

        // Calling updateBalance should propagate the exception
        assertThrows(RuntimeException.class, () ->
                accountService.updateBalance(testAccountNumber, updateAmount, opKey));

        // Clear JPA first-level cache to force real database query
        entityManager.clear();

        // Verify committed database state:
        // 1. Account balance is rolled back to original 1000.00
        Account accountFromDb = accountService.getAccount(testAccountNumber);
        assertEquals(0, initialBalance.compareTo(accountFromDb.getBalance()),
                "Balance must roll back to original value of 1000.00, but was " + accountFromDb.getBalance());

        // 2. No BalanceOperation record exists in DB
        assertFalse(balanceOperationRepository.existsByOperationKey(opKey),
                "No BalanceOperation record must exist in DB after transaction rollback");
    }
}
