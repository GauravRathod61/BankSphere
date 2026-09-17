package com.banking.account_service.integration;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.model.Account;
import com.banking.account_service.model.BalanceOperation;
import com.banking.account_service.repository.AccountRepository;
import com.banking.account_service.repository.BalanceOperationRepository;
import com.banking.account_service.service.AccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@EnabledIf("isDockerAvailable")
class AccountPostgreSqlIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_account_db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (isDockerAvailable() && postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        }
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BalanceOperationRepository balanceOperationRepository;

    @Test
    void testAccountAndBalanceOperationPersistenceOnRealPostgreSql() {
        CreateAccountDto dto = new CreateAccountDto();
        dto.setCustomerId(500L);
        dto.setAccountType(Account.AccountType.SAVINGS);

        Account account = accountService.createAccount(dto);
        assertNotNull(account.getAccountNumber());
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getBalance()));
        assertEquals(0L, account.getVersion());

        // Test balance update and operation idempotency record
        String opKey = "pg-op-" + System.currentTimeMillis();
        accountService.updateBalance(account.getAccountNumber(), new BigDecimal("250.00"), opKey);

        Account updated = accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow();
        assertEquals(0, new BigDecimal("250.00").compareTo(updated.getBalance()));
        assertTrue(updated.getVersion() > 0L, "Version should increment on real PostgreSQL update");

        // Verify balance operation record persisted in PostgreSQL
        Optional<BalanceOperation> opRecord = balanceOperationRepository.findByOperationKey(opKey);
        assertTrue(opRecord.isPresent());
        assertEquals(account.getAccountNumber(), opRecord.get().getAccountNumber());
        assertEquals(0, new BigDecimal("250.00").compareTo(opRecord.get().getAmount()));
    }
}
