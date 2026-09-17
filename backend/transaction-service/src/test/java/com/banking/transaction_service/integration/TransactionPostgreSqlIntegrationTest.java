package com.banking.transaction_service.integration;

import com.banking.transaction_service.model.Transaction;
import com.banking.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@EnabledIf("isDockerAvailable")
class TransactionPostgreSqlIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_tx_db")
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
    private TransactionRepository transactionRepository;

    @Test
    void testTransactionPersistenceAndIdempotencyOnRealPostgreSql() {
        String txId = UUID.randomUUID().toString();
        String idemKey = "idem-" + System.currentTimeMillis();

        Transaction tx = new Transaction();
        tx.setTransactionId(txId);
        tx.setSourceAccountNumber("ACC_PG_1");
        tx.setTargetAccountNumber("ACC_PG_2");
        tx.setAmount(new BigDecimal("150.00"));
        tx.setType(Transaction.TransactionType.TRANSFER);
        tx.setDescription("PostgreSQL test transfer");
        tx.setStatus(Transaction.TransactionStatus.SUCCESS);
        tx.setIdempotencyKey(idemKey);

        Transaction saved = transactionRepository.saveAndFlush(tx);
        assertNotNull(saved.getId());
        assertEquals(txId, saved.getTransactionId());

        // Test Unique Constraint on Idempotency Key in real PostgreSQL
        Transaction duplicate = new Transaction();
        duplicate.setTransactionId(UUID.randomUUID().toString());
        duplicate.setSourceAccountNumber("ACC_PG_1");
        duplicate.setTargetAccountNumber("ACC_PG_2");
        duplicate.setAmount(new BigDecimal("150.00"));
        duplicate.setType(Transaction.TransactionType.TRANSFER);
        duplicate.setStatus(Transaction.TransactionStatus.PENDING);
        duplicate.setIdempotencyKey(idemKey);

        assertThrows(DataIntegrityViolationException.class, () -> transactionRepository.saveAndFlush(duplicate));

        // Test statement query queries on real PostgreSQL
        Page<Transaction> history = transactionRepository.findBySourceAccountNumberOrTargetAccountNumberOrderByTimestampDesc(
                "ACC_PG_1", "ACC_PG_1", PageRequest.of(0, 10));
        assertEquals(1, history.getTotalElements());

        List<Transaction> mini = transactionRepository.findTop10BySourceAccountNumberOrTargetAccountNumberOrderByTimestampDesc(
                "ACC_PG_1", "ACC_PG_1");
        assertEquals(1, mini.size());
    }
}
