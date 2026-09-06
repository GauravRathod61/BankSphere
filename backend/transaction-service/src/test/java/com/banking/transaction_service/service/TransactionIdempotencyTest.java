package com.banking.transaction_service.service;

import com.banking.transaction_service.dto.TransactionRequestDto;
import com.banking.transaction_service.model.Transaction;
import com.banking.transaction_service.repository.TransactionRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TransactionIdempotencyTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(9090);
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetState() {
        if (!wireMockServer.isRunning()) {
            wireMockServer.start();
        }
        wireMockServer.resetAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
        transactionRepository.deleteAll();

        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .willReturn(aResponse().withStatus(200)));
    }

    @Test
    void testSequentialSameKey_ReturnsExistingTransaction() {
        String key = "KEY-SEQ-" + UUID.randomUUID();
        TransactionRequestDto dto = createSampleDto();

        // 1st request
        Transaction tx1 = transactionService.processTransaction(dto, key);
        // 2nd request with same key
        Transaction tx2 = transactionService.processTransaction(dto, key);

        // Exactly 1 transaction row in DB
        assertEquals(1, transactionRepository.count());

        // Identity fields match
        assertEquals(tx1.getTransactionId(), tx2.getTransactionId());
        assertEquals(key, tx1.getIdempotencyKey());
        assertEquals(key, tx2.getIdempotencyKey());

        // Account service called exactly 1 time
        wireMockServer.verify(1, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testConcurrentSameKey_ReturnsSameTransactionIdentity() throws Exception {
        String key = "KEY-CONC-" + UUID.randomUUID();
        TransactionRequestDto dto = createSampleDto();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        Future<Transaction> future1 = executor.submit(() -> {
            startLatch.await();
            return transactionService.processTransaction(dto, key);
        });
        Future<Transaction> future2 = executor.submit(() -> {
            startLatch.await();
            return transactionService.processTransaction(dto, key);
        });

        // Release both threads simultaneously
        startLatch.countDown();

        Transaction tx1 = future1.get(10, TimeUnit.SECONDS);
        Transaction tx2 = future2.get(10, TimeUnit.SECONDS);

        executor.shutdown();

        // Identity & Deduplication assertions only
        assertEquals(tx1.getTransactionId(), tx2.getTransactionId(), "Both responses must have the same transactionId");
        assertEquals(key, tx1.getIdempotencyKey(), "Response 1 idempotencyKey must match");
        assertEquals(key, tx2.getIdempotencyKey(), "Response 2 idempotencyKey must match");

        // Exactly one transaction row exists in database
        assertEquals(1, transactionRepository.count(), "Exactly one transaction row must exist in the database");

        // Account service called exactly once
        wireMockServer.verify(1, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testNoIdempotencyKey_CreatesIndependentTransactions() {
        TransactionRequestDto dto1 = createSampleDto();
        TransactionRequestDto dto2 = createSampleDto();

        Transaction tx1 = transactionService.processTransaction(dto1, null);
        Transaction tx2 = transactionService.processTransaction(dto2, null);

        // Both requests succeed cleanly and distinct transaction rows created
        assertNotNull(tx1.getTransactionId());
        assertNotNull(tx2.getTransactionId());
        assertNotEquals(tx1.getTransactionId(), tx2.getTransactionId(), "Transaction IDs must be distinct");
        assertNull(tx1.getIdempotencyKey(), "Idempotency key must be null");
        assertNull(tx2.getIdempotencyKey(), "Idempotency key must be null");

        // Two rows in database
        assertEquals(2, transactionRepository.count(), "Two distinct transaction rows must exist in database");

        // Account service called twice (1 for each transaction)
        wireMockServer.verify(2, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testDifferentIdempotencyKeys_CreatesSeparateTransactions() {
        String keyA = "KEY-A-" + UUID.randomUUID();
        String keyB = "KEY-B-" + UUID.randomUUID();

        TransactionRequestDto dto1 = createSampleDto();
        TransactionRequestDto dto2 = createSampleDto();

        Transaction tx1 = transactionService.processTransaction(dto1, keyA);
        Transaction tx2 = transactionService.processTransaction(dto2, keyB);

        // Distinct transactions
        assertNotEquals(tx1.getTransactionId(), tx2.getTransactionId());
        assertEquals(keyA, tx1.getIdempotencyKey());
        assertEquals(keyB, tx2.getIdempotencyKey());

        // Two rows in database
        assertEquals(2, transactionRepository.count());

        // Account service called twice
        wireMockServer.verify(2, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    private TransactionRequestDto createSampleDto() {
        TransactionRequestDto dto = new TransactionRequestDto();
        dto.setSourceAccountNumber("ACC001");
        dto.setTargetAccountNumber("ACC002");
        dto.setAmount(new BigDecimal("50.00"));
        dto.setType(Transaction.TransactionType.DEPOSIT);
        dto.setDescription("Test Idempotent Transaction");
        return dto;
    }
}
