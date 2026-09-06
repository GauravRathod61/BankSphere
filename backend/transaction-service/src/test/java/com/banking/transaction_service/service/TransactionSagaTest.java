package com.banking.transaction_service.service;

import com.banking.transaction_service.dto.TransactionRequestDto;
import com.banking.transaction_service.model.Transaction;
import com.banking.transaction_service.repository.TransactionRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TransactionSagaTest {

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
    }

    @Test
    void testSuccessfulTransfer_DebitAndCreditSucceed() {
        wireMockServer.stubFor(post(urlMatching("/accounts/.*/update-balance"))
                .willReturn(aResponse().withStatus(200)));

        TransactionRequestDto dto = createTransferDto("ACC001", "ACC002", "100.00");
        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.SUCCESS, tx.getStatus());
        // 2 calls: 1 DEBIT, 1 CREDIT
        wireMockServer.verify(2, postRequestedFor(urlMatching("/accounts/.*/update-balance")));
    }

    @Test
    void testCreditRejection_TriggersCompensation() {
        // DEBIT succeeds
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .willReturn(aResponse().withStatus(200)));
        // CREDIT rejected (400 Bad Request)
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC002/update-balance"))
                .willReturn(aResponse().withStatus(400)));

        TransactionRequestDto dto = createTransferDto("ACC001", "ACC002", "100.00");
        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.REVERSED, tx.getStatus());
        assertNotNull(tx.getCompensationTransactionId(), "Compensation transaction ID must be present");

        // Verify compensation transaction created in DB
        List<Transaction> all = transactionRepository.findAll();
        assertEquals(2, all.size(), "Two transaction records must exist (original + compensation)");

        // Verify DEBIT-COMPENSATION request sent to account service for ACC001
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .withRequestBody(matchingJsonPath("$.operationKey", matching(".*-DEBIT-COMPENSATION"))));
    }

    @Test
    void testCreditConnectionFailure_TriggersCompensation() {
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .willReturn(aResponse().withStatus(200)));
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC002/update-balance"))
                .willReturn(aResponse().withStatus(503))); // unavailable / connection error

        TransactionRequestDto dto = createTransferDto("ACC001", "ACC002", "100.00");
        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.REVERSED, tx.getStatus());
        assertNotNull(tx.getCompensationTransactionId());
    }

    @Test
    void testAmbiguousCredit_SameKeyReconciliationSucceeds() {
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .willReturn(aResponse().withStatus(200)));

        // CREDIT times out on 1st call, succeeds on 2nd call (same-key reconciliation)
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC002/update-balance"))
                .inScenario("CreditReconciliation")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(3500))
                .willSetStateTo("RECONCILE"));

        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC002/update-balance"))
                .inScenario("CreditReconciliation")
                .whenScenarioStateIs("RECONCILE")
                .willReturn(aResponse().withStatus(200)));

        TransactionRequestDto dto = createTransferDto("ACC001", "ACC002", "100.00");
        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.SUCCESS, tx.getStatus());
        // 1 DEBIT + 2 CREDIT calls
        wireMockServer.verify(2, postRequestedFor(urlPathEqualTo("/accounts/ACC002/update-balance")));
    }

    @Test
    void testAmbiguousDebit_ReconciliationApplied_ContinuesToCredit() {
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC002/update-balance"))
                .willReturn(aResponse().withStatus(200)));

        // DEBIT times out on 1st call, succeeds on 2nd call (reconciliation)
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .inScenario("DebitReconciliation")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(3500))
                .willSetStateTo("RECONCILE"));

        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .inScenario("DebitReconciliation")
                .whenScenarioStateIs("RECONCILE")
                .willReturn(aResponse().withStatus(200)));

        TransactionRequestDto dto = createTransferDto("ACC001", "ACC002", "100.00");
        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.SUCCESS, tx.getStatus());
    }

    @Test
    void testAmbiguousDebit_ReconciliationNotApplied_FailedNoCompensation() {
        // DEBIT times out on 1st call, 400 Bad Request on 2nd call (reconciliation)
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .inScenario("DebitFailedReconcile")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(3500))
                .willSetStateTo("RECONCILE"));

        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .inScenario("DebitFailedReconcile")
                .whenScenarioStateIs("RECONCILE")
                .willReturn(aResponse().withStatus(400)));

        TransactionRequestDto dto = createTransferDto("ACC001", "ACC002", "100.00");
        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.FAILED, tx.getStatus());
        assertNull(tx.getCompensationTransactionId(), "NO compensation should be attempted");
    }

    @Test
    void testCreditDoubleAmbiguity_ManualReviewNoCompensation() {
        // DEBIT succeeds
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .willReturn(aResponse().withStatus(200)));

        // CREDIT times out on 1st call AND times out on reconciliation
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC002/update-balance"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3500)));

        TransactionRequestDto dto = createTransferDto("ACC001", "ACC002", "100.00");
        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.FAILED_NEEDS_MANUAL_REVIEW, tx.getStatus());
        // Verify ZERO compensation calls were made (ACC001 received only 1 request for DEBIT)
        wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .withRequestBody(matchingJsonPath("$.operationKey", matching(".*-DEBIT-COMPENSATION"))));
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/accounts/ACC001/update-balance")));
    }

    @Test
    void testCompensationFailure_NeedsManualReview() {
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .inScenario("CompFailure")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200))
                .willSetStateTo("COMP"));

        // CREDIT fails (400)
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC002/update-balance"))
                .willReturn(aResponse().withStatus(400)));

        // COMPENSATION fails (400)
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .inScenario("CompFailure")
                .whenScenarioStateIs("COMP")
                .willReturn(aResponse().withStatus(400)));

        TransactionRequestDto dto = createTransferDto("ACC001", "ACC002", "100.00");
        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.FAILED_NEEDS_MANUAL_REVIEW, tx.getStatus());
    }

    @Test
    void testAmbiguousDepositReconciliation_Succeeds() {
        // DEPOSIT times out 1st call, succeeds on 2nd call (reconciliation)
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .inScenario("DepositReconciliation")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(3500))
                .willSetStateTo("RECONCILE"));

        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .inScenario("DepositReconciliation")
                .whenScenarioStateIs("RECONCILE")
                .willReturn(aResponse().withStatus(200)));

        TransactionRequestDto dto = new TransactionRequestDto();
        dto.setSourceAccountNumber("ACC001");
        dto.setAmount(new BigDecimal("100.00"));
        dto.setType(Transaction.TransactionType.DEPOSIT);
        dto.setDescription("Test Deposit");

        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.SUCCESS, tx.getStatus());
    }

    @Test
    void testCompensationIdempotency_SameKeyNoDuplicateRefund() {
        // DEBIT succeeds
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .willReturn(aResponse().withStatus(200)));

        // CREDIT fails (400)
        wireMockServer.stubFor(post(urlPathEqualTo("/accounts/ACC002/update-balance"))
                .willReturn(aResponse().withStatus(400)));

        TransactionRequestDto dto = createTransferDto("ACC001", "ACC002", "100.00");
        Transaction tx = transactionService.processTransaction(dto);

        assertEquals(Transaction.TransactionStatus.REVERSED, tx.getStatus());
        String compTxId = tx.getCompensationTransactionId();
        assertNotNull(compTxId);

        // Verify compensation call used specific operationKey format {transactionId}-DEBIT-COMPENSATION
        String expectedCompKey = tx.getTransactionId() + "-DEBIT-COMPENSATION";
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/accounts/ACC001/update-balance"))
                .withRequestBody(matchingJsonPath("$.operationKey", equalTo(expectedCompKey))));
    }

    private TransactionRequestDto createTransferDto(String source, String target, String amount) {
        TransactionRequestDto dto = new TransactionRequestDto();
        dto.setSourceAccountNumber(source);
        dto.setTargetAccountNumber(target);
        dto.setAmount(new BigDecimal(amount));
        dto.setType(Transaction.TransactionType.TRANSFER);
        dto.setDescription("Test Transfer");
        return dto;
    }
}
