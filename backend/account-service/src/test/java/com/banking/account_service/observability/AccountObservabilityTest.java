package com.banking.account_service.observability;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.filter.CorrelationIdFilter;
import com.banking.account_service.model.Account;
import com.banking.account_service.service.AccountService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AccountObservabilityTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Value("${banking.security.jwt.secret}")
    private String jwtSecret;

    private MockMvc mockMvc;
    private String serviceToken;
    private String customerToken;
    private Account testAccount;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(correlationIdFilter)
                .apply(springSecurity())
                .build();

        serviceToken = generateToken("transaction-service", "SERVICE", List.of("ROLE_SERVICE"), 300);
        customerToken = generateToken("100", "CUSTOMER", List.of("ROLE_CUSTOMER"), 3600);

        CreateAccountDto dto = new CreateAccountDto();
        dto.setCustomerId(100L);
        dto.setAccountType(Account.AccountType.CURRENT);
        testAccount = accountService.createAccount(dto);
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
    void testCorrelationIdPreservationAndEcho() throws Exception {
        String clientCorrId = "custom-acc-trace-555";
        String updateJson = "{\"amount\":100.00,\"operationKey\":\"obs-op-1\"}";

        mockMvc.perform(post("/accounts/" + testAccount.getAccountNumber() + "/update-balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, clientCorrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, clientCorrId));

        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), "MDC must be cleared after success");
    }

    @Test
    void testMdcCleanup_OnSuccessAndFailure() throws Exception {
        // 1. Success path
        String updateJson = "{\"amount\":50.00,\"operationKey\":\"obs-op-2\"}";
        mockMvc.perform(post("/accounts/" + testAccount.getAccountNumber() + "/update-balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));

        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), "MDC must be cleared after 200 OK");

        // 2. Exception/Security failure path (403 Forbidden with customer token)
        mockMvc.perform(post("/accounts/" + testAccount.getAccountNumber() + "/update-balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isForbidden())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));

        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), "MDC must be cleared after 403 Forbidden");
    }

    @Test
    void testBusinessMetricsIncrement() throws Exception {
        double initialCreated = getCounterCount("banking.accounts.created", "type", "CURRENT");
        double initialBalanceSuccess = getCounterCount("banking.account.balance.updates", "status", "SUCCESS");
        double initialBalanceFail = getCounterCount("banking.account.balance.updates", "status", "FAILED");

        // 1. Create Account via controller
        String createJson = "{\"customerId\":100,\"accountType\":\"CURRENT\"}";
        mockMvc.perform(post("/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated());

        assertEquals(initialCreated + 1, getCounterCount("banking.accounts.created", "type", "CURRENT"));

        // 2. Balance update success
        String updateSuccessJson = "{\"amount\":200.00,\"operationKey\":\"obs-metric-op-success\"}";
        mockMvc.perform(post("/accounts/" + testAccount.getAccountNumber() + "/update-balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSuccessJson))
                .andExpect(status().isOk());

        assertEquals(initialBalanceSuccess + 1, getCounterCount("banking.account.balance.updates", "status", "SUCCESS"));

        // 3. Balance update failure (insufficient funds: debit 999999)
        String updateFailJson = "{\"amount\":-999999.00,\"operationKey\":\"obs-metric-op-fail\"}";
        mockMvc.perform(post("/accounts/" + testAccount.getAccountNumber() + "/update-balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateFailJson))
                .andExpect(status().isBadRequest());

        assertEquals(initialBalanceFail + 1, getCounterCount("banking.account.balance.updates", "status", "FAILED"));
    }

    private double getCounterCount(String name, String tagKey, String tagValue) {
        var counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter != null ? counter.count() : 0.0;
    }
}
