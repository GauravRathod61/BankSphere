package com.banking.transaction_service.service;

import com.banking.transaction_service.dto.TransactionRequestDto;
import com.banking.transaction_service.exception.AccountServiceUnavailableException;
import com.banking.transaction_service.model.Transaction;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TransactionServiceResilienceTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private TransactionService transactionService;

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
    }

    @Test
    void testConnectionFailure_RetriesAndThrowsException() {
        // Stop WireMock to simulate connection failure (Connection Refused)
        wireMockServer.stop();

        TransactionRequestDto dto = createSampleDto();

        assertThrows(AccountServiceUnavailableException.class, () ->
                transactionService.processTransaction(dto));
    }

    @Test
    void testReadTimeout_NoRetryAndThrowsException() {
        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3000))); // Delay longer than 1500ms read timeout

        TransactionRequestDto dto = createSampleDto();

        assertThrows(AccountServiceUnavailableException.class, () ->
                transactionService.processTransaction(dto));

        // Verify request was attempted EXACTLY 1 time (NO retry on read timeout)
        wireMockServer.verify(1, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testHttp5xxError_NoRetryAndThrowsException() {
        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Internal Server Error\"}")));

        TransactionRequestDto dto = createSampleDto();

        assertThrows(AccountServiceUnavailableException.class, () ->
                transactionService.processTransaction(dto));

        // Verify request was attempted EXACTLY 1 time (NO retry on HTTP 5xx)
        wireMockServer.verify(1, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testCircuitBreakerOpens_FailsFast() {
        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .willReturn(aResponse().withStatus(500)));

        TransactionRequestDto dto = createSampleDto();

        // Perform 5 consecutive failed calls to trigger circuit breaker opening (sliding-window-size = 5)
        for (int i = 0; i < 5; i++) {
            assertThrows(AccountServiceUnavailableException.class, () ->
                    transactionService.processTransaction(dto));
        }

        // Verify WireMock received 5 requests
        wireMockServer.verify(5, postRequestedFor(urlMatching("/accounts/.*/update-balance")));

        // 6th call: Circuit breaker is now OPEN. It should fail fast without making a network call to WireMock
        assertThrows(AccountServiceUnavailableException.class, () ->
                transactionService.processTransaction(dto));

        // Verify WireMock request count did NOT increase (remains 5)
        wireMockServer.verify(5, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testCircuitBreakerWithRetries_OpensAfter5LogicalCalls() {
        // Stop WireMock to simulate connection failure (Connection Refused)
        wireMockServer.stop();

        TransactionRequestDto dto = createSampleDto();

        // Perform 4 logical calls (each call exhausts 3 retries)
        for (int i = 0; i < 4; i++) {
            assertThrows(AccountServiceUnavailableException.class, () ->
                    transactionService.processTransaction(dto));
            assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED,
                    circuitBreakerRegistry.circuitBreaker("accountService").getState(),
                    "Circuit breaker must remain CLOSED before reaching 5 logical calls");
        }

        // 5th logical call (exhausts 3 retries)
        assertThrows(AccountServiceUnavailableException.class, () ->
                transactionService.processTransaction(dto));

        // Verify CircuitBreaker is NOW OPEN after 5 logical calls
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN,
                circuitBreakerRegistry.circuitBreaker("accountService").getState(),
                "Circuit breaker must be OPEN after 5 logical calls fail");
    }

    private TransactionRequestDto createSampleDto() {
        TransactionRequestDto dto = new TransactionRequestDto();
        dto.setSourceAccountNumber("ACC001");
        dto.setTargetAccountNumber("ACC002");
        dto.setAmount(new BigDecimal("100.00"));
        dto.setType(Transaction.TransactionType.DEPOSIT);
        dto.setDescription("Test Deposit");
        return dto;
    }
}
