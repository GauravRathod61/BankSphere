package com.banking.transaction_service.service;

import com.banking.transaction_service.client.AccountServiceClient;
import com.banking.transaction_service.exception.AccountServiceRejectedException;
import com.banking.transaction_service.exception.AccountServiceSecurityException;
import com.banking.transaction_service.exception.AccountServiceTimeoutException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
class ServiceTokenExpiryAndSecurityTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private AccountServiceClient accountServiceClient;

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
    void testScenario1_TokenExpired_SuccessfulRetry() {
        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .inScenario("TokenExpiryRetry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"The token is expired\", error_uri=\"https://tools.ietf.org/html/rfc6750#section-3.1\""))
                .willSetStateTo("TokenRefreshed"));

        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .inScenario("TokenExpiryRetry")
                .whenScenarioStateIs("TokenRefreshed")
                .willReturn(aResponse()
                        .withStatus(200)));

        assertDoesNotThrow(() ->
                accountServiceClient.updateAccountBalance("ACC001", new BigDecimal("100.00"), "test-key-exp-success"));

        wireMockServer.verify(2, postRequestedFor(urlMatching("/accounts/.*/update-balance")));

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("accountService");
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(0, cb.getMetrics().getNumberOfFailedCalls());
    }

    @Test
    void testScenario2_TokenExpired_ReadTimeoutOnRetry_ClassifiedAsTimeoutException() {
        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .inScenario("TokenExpiryTimeout")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"The token is expired\""))
                .willSetStateTo("TokenRefreshed"));

        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .inScenario("TokenExpiryTimeout")
                .whenScenarioStateIs("TokenRefreshed")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3500)));

        assertThrows(AccountServiceTimeoutException.class, () ->
                accountServiceClient.updateAccountBalance("ACC001", new BigDecimal("100.00"), "test-key-exp-timeout"));

        wireMockServer.verify(2, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testScenario3_TokenExpired_BusinessRejectionOnRetry_ClassifiedAsRejectedException() {
        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .inScenario("TokenExpiry4xx")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"The token is expired\""))
                .willSetStateTo("TokenRefreshed"));

        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .inScenario("TokenExpiry4xx")
                .whenScenarioStateIs("TokenRefreshed")
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Insufficient funds\"}")));

        assertThrows(AccountServiceRejectedException.class, () ->
                accountServiceClient.updateAccountBalance("ACC001", new BigDecimal("100.00"), "test-key-exp-4xx"));

        wireMockServer.verify(2, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testScenario4_NonExpiry401_ThrowsSecurityExceptionWithoutRetry() {
        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"Signature verification failed\"")));

        assertThrows(AccountServiceSecurityException.class, () ->
                accountServiceClient.updateAccountBalance("ACC001", new BigDecimal("100.00"), "test-key-sig-fail"));

        wireMockServer.verify(1, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testScenario5_Forbidden403_ThrowsSecurityExceptionWithoutRetry() {
        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Forbidden\"}")));

        assertThrows(AccountServiceSecurityException.class, () ->
                accountServiceClient.updateAccountBalance("ACC001", new BigDecimal("100.00"), "test-key-403"));

        wireMockServer.verify(1, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }
}
