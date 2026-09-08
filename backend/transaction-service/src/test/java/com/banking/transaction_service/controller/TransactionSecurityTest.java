package com.banking.transaction_service.controller;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.github.tomakehurst.wiremock.client.WireMock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
public class TransactionSecurityTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Value("${banking.security.jwt.secret}")
    private String jwtSecret;

    private String customer1Token;
    private String customer2Token;
    private String adminToken;

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

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        customer1Token = generateToken("100", "CUSTOMER", List.of("ROLE_CUSTOMER"), 3600);
        customer2Token = generateToken("200", "CUSTOMER", List.of("ROLE_CUSTOMER"), 3600);
        adminToken = generateToken("999", "ADMIN", List.of("ROLE_ADMIN"), 3600);

        // WireMock stubs for account lookups
        wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/accounts/ACC001"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountNumber\":\"ACC001\",\"customerId\":100,\"balance\":5000.00,\"status\":\"ACTIVE\"}")));

        wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/accounts/ACC002"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountNumber\":\"ACC002\",\"customerId\":200,\"balance\":5000.00,\"status\":\"ACTIVE\"}")));

        // WireMock stub for balance updates
        wireMockServer.stubFor(WireMock.post(WireMock.urlMatching("/accounts/.*/update-balance"))
                .willReturn(WireMock.aResponse().withStatus(200)));
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
    void testUnauthenticatedRequests_Return401() throws Exception {
        // Missing Authorization header
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountNumber\":\"ACC001\",\"amount\":100.00,\"type\":\"WITHDRAW\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/transactions/account/ACC001"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/transactions/mini-statement/ACC001"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/transactions/monthly-statement/ACC001?year=2026&month=1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testWithdraw_BOLA_CustomerAccessingOtherAccount_Returns403() throws Exception {
        // Customer 100 attempting WITHDRAW from Customer 200's account -> 403 Forbidden
        String jsonWithdrawOther = "{\"sourceAccountNumber\":\"ACC002\",\"amount\":100.00,\"type\":\"WITHDRAW\"}";
        mockMvc.perform(post("/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithdrawOther))
                .andExpect(status().isForbidden());

        // Customer 100 attempting WITHDRAW from own account -> 201 Created
        String jsonWithdrawOwn = "{\"sourceAccountNumber\":\"ACC001\",\"amount\":100.00,\"type\":\"WITHDRAW\"}";
        mockMvc.perform(post("/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithdrawOwn))
                .andExpect(status().isCreated());
    }

    @Test
    void testTransfer_BOLA_CustomerTransferringFromOtherAccount_Returns403() throws Exception {
        // Customer 100 attempting TRANSFER from Customer 200's account -> 403 Forbidden
        String jsonTransferOther = "{\"sourceAccountNumber\":\"ACC002\",\"destinationAccountNumber\":\"ACC001\",\"amount\":100.00,\"type\":\"TRANSFER\"}";
        mockMvc.perform(post("/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonTransferOther))
                .andExpect(status().isForbidden());

        // Customer 100 attempting TRANSFER from own account -> 201 Created
        String jsonTransferOwn = "{\"sourceAccountNumber\":\"ACC001\",\"destinationAccountNumber\":\"ACC002\",\"amount\":100.00,\"type\":\"TRANSFER\"}";
        mockMvc.perform(post("/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonTransferOwn))
                .andExpect(status().isCreated());
    }

    @Test
    void testHistoryAndStatements_BOLA_CustomerAccessingOtherAccount_Returns403() throws Exception {
        // Customer 100 accessing Customer 200's history -> 403 Forbidden
        mockMvc.perform(get("/transactions/account/ACC002")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                .andExpect(status().isForbidden());

        // Customer 100 accessing own history -> 200 OK
        mockMvc.perform(get("/transactions/account/ACC001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                .andExpect(status().isOk());

        // Customer 100 accessing Customer 200's mini-statement -> 403 Forbidden
        mockMvc.perform(get("/transactions/mini-statement/ACC002")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                .andExpect(status().isForbidden());

        // Customer 100 accessing own mini-statement -> 200 OK
        mockMvc.perform(get("/transactions/mini-statement/ACC001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                .andExpect(status().isOk());

        // Customer 100 accessing Customer 200's monthly-statement -> 403 Forbidden
        mockMvc.perform(get("/transactions/monthly-statement/ACC002?year=2026&month=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                .andExpect(status().isForbidden());

        // Customer 100 accessing own monthly-statement -> 200 OK
        mockMvc.perform(get("/transactions/monthly-statement/ACC001?year=2026&month=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                .andExpect(status().isOk());
    }

    @Test
    void testAdminAccess_CanAccessAnyAccount() throws Exception {
        // Admin accessing Customer 200's history -> 200 OK
        mockMvc.perform(get("/transactions/account/ACC002")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Admin accessing Customer 200's mini-statement -> 200 OK
        mockMvc.perform(get("/transactions/mini-statement/ACC002")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Admin accessing Customer 200's monthly-statement -> 200 OK
        mockMvc.perform(get("/transactions/monthly-statement/ACC002?year=2026&month=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void testDeposit_CustomerCanDepositIntoAnyAccount_Allowed() throws Exception {
        // Intentional design: DEPOSIT is a credit operation and does not debit caller funds,
        // so an authenticated customer is permitted to deposit into another customer's account.
        String jsonDepositOther = "{\"sourceAccountNumber\":\"ACC002\",\"amount\":100.00,\"type\":\"DEPOSIT\"}";
        mockMvc.perform(post("/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonDepositOther))
                .andExpect(status().isCreated());
    }
}
