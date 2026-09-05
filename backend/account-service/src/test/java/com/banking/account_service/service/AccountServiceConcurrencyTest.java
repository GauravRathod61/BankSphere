package com.banking.account_service.service;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.model.Account;
import com.banking.account_service.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class AccountServiceConcurrencyTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    private String testAccountNumber;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();

        CreateAccountDto dto = new CreateAccountDto();
        dto.setCustomerId(100L);
        dto.setAccountType(Account.AccountType.CURRENT);

        Account account = accountService.createAccount(dto);
        testAccountNumber = account.getAccountNumber();

        // Initialize balance to 1000.00
        accountService.updateBalance(testAccountNumber, new BigDecimal("1000.00"));
    }

    @Test
    void testConcurrentBalanceUpdates_NoLostUpdates() throws Exception {
        int numberOfThreads = 20;
        BigDecimal incrementAmount = new BigDecimal("10.00");
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numberOfThreads);

        for (int i = 0; i < numberOfThreads; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    accountService.updateBalance(testAccountNumber, incrementAmount);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();

        boolean finishedInTime = finishLatch.await(30, TimeUnit.SECONDS);
        assertTrue(finishedInTime, "Concurrent balance updates did not complete within timeout");
        executorService.shutdown();

        // Verify final balance using numeric BigDecimal comparison
        Account finalAccount = accountService.getAccount(testAccountNumber);
        BigDecimal expectedBalance = new BigDecimal("1200.00"); // 1000.00 + (20 * 10.00)

        assertEquals(0, expectedBalance.compareTo(finalAccount.getBalance()),
                "Final balance must equal expected balance of " + expectedBalance + ", but was " + finalAccount.getBalance());
    }
}
