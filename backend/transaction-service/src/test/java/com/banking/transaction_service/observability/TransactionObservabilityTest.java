package com.banking.transaction_service.observability;

import com.banking.transaction_service.filter.CorrelationIdFilter;
import com.banking.transaction_service.repository.TransactionRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class TransactionObservabilityTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private TransactionRepository transactionRepository;

    @Value("${banking.security.jwt.secret}")
    private String jwtSecret;

    private MockMvc mockMvc;
    private String customer100Token;

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
    void setUp() throws Exception {
        if (!wireMockServer.isRunning()) {
            wireMockServer.start();
        }
        wireMockServer.resetAll();
        transactionRepository.deleteAll();

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(correlationIdFilter)
                .apply(springSecurity())
                .build();

        customer100Token = generateToken("100", "CUSTOMER", List.of("ROLE_CUSTOMER"), 3600);

        // Account 100 lookup stub
        wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/accounts/ACC001"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountNumber\":\"ACC001\",\"customerId\":100,\"balance\":5000.00,\"status\":\"ACTIVE\"}")));

        // Balance update stub
        wireMockServer.stubFor(WireMock.post(WireMock.urlMatching("/accounts/.*/update-balance"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountNumber\":\"ACC001\",\"balance\":5100.00}")));
    }

    private String generateToken(String sub, String role, List<String> roles, long expirationSeconds) throws Exception {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expirationSeconds);
        SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        JWSSigner signer = new MACSigner(key);
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(sub)
                .claim("role", role)
                .claim("roles", roles)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        signedJWT.sign(signer);
        return signedJWT.serialize();
    }

    @Test
    void testMultiHopCorrelationPropagation_WhenHeaderMissing_GeneratesAndPropagatesToDownstream() throws Exception {
        String txJson = """
                {
                    "sourceAccountNumber": "ACC001",
                    "amount": 100.00,
                    "type": "DEPOSIT",
                    "description": "Test generated correlation propagation"
                }
                """;

        MvcResult result = mockMvc.perform(post("/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer100Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson))
                .andExpect(status().isCreated())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andReturn();

        String responseCorrId = result.getResponse().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertNotNull(responseCorrId);
        assertFalse(responseCorrId.trim().isEmpty());

        // Verify downstream WireMock received EXACT same generated correlation ID
        wireMockServer.verify(WireMock.postRequestedFor(WireMock.urlMatching("/accounts/ACC001/update-balance"))
                .withHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, WireMock.equalTo(responseCorrId)));

        // Verify MDC cleanup
        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), "MDC must be cleared after request");
    }

    @Test
    void testMultiHopCorrelationPropagation_WhenHeaderPresent_PreservesAndPropagatesToDownstream() throws Exception {
        String customCorrId = "custom-tx-trace-777";
        String txJson = """
                {
                    "sourceAccountNumber": "ACC001",
                    "amount": 50.00,
                    "type": "DEPOSIT",
                    "description": "Test preserved correlation propagation"
                }
                """;

        mockMvc.perform(post("/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer100Token)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, customCorrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson))
                .andExpect(status().isCreated())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, customCorrId));

        // Verify downstream WireMock received EXACT same client correlation ID
        wireMockServer.verify(WireMock.postRequestedFor(WireMock.urlMatching("/accounts/ACC001/update-balance"))
                .withHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, WireMock.equalTo(customCorrId)));

        // Verify MDC cleanup
        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), "MDC must be cleared after request");
    }

    @Test
    void testMdcCleanup_OnSuccessAndExceptionPaths() throws Exception {
        // 1. Success path
        mockMvc.perform(get("/transactions/mini-statement/ACC001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer100Token))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));

        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), "MDC must be cleaned on success");

        // 2. Exception / BOLA Forbidden path (accessing account not owned by customer 100)
        wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/accounts/ACC999"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountNumber\":\"ACC999\",\"customerId\":999,\"balance\":1000.00,\"status\":\"ACTIVE\"}")));

        mockMvc.perform(get("/transactions/mini-statement/ACC999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer100Token))
                .andExpect(status().isForbidden())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));

        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), "MDC must be cleaned on 403 Forbidden");
    }

    @Test
    void testBusinessMetricsIncrement() throws Exception {
        double initialDepositSuccess = getCounterCount("banking.transactions.processed", "type", "DEPOSIT", "status", "SUCCESS");

        String txJson = """
                {
                    "sourceAccountNumber": "ACC001",
                    "amount": 25.00,
                    "type": "DEPOSIT",
                    "description": "Test metrics"
                }
                """;

        mockMvc.perform(post("/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer100Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson))
                .andExpect(status().isCreated());

        double afterDepositSuccess = getCounterCount("banking.transactions.processed", "type", "DEPOSIT", "status", "SUCCESS");
        assertEquals(initialDepositSuccess + 1.0, afterDepositSuccess, 0.001);

        // Verify timer recorded
        Timer timer = meterRegistry.find("banking.transactions.duration").tag("type", "DEPOSIT").timer();
        assertNotNull(timer, "Timer banking.transactions.duration should be registered");
        assertTrue(timer.count() > 0, "Timer should have recorded at least 1 execution");
    }

    private double getCounterCount(String name, String tag1Key, String tag1Val, String tag2Key, String tag2Val) {
        var counter = meterRegistry.find(name).tag(tag1Key, tag1Val).tag(tag2Key, tag2Val).counter();
        return counter != null ? counter.count() : 0.0;
    }
}
