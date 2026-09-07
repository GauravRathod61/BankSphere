package com.banking.account_service.controller;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.model.Account;
import com.banking.account_service.service.AccountService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
public class AccountSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Value("${banking.security.jwt.secret}")
    private String jwtSecret;

    private String customer1Token;
    private String customer2Token;
    private String adminToken;
    private String serviceToken;
    private Account customer1Account;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        customer1Token = generateToken("100", "CUSTOMER", List.of("ROLE_CUSTOMER"), 3600);
        customer2Token = generateToken("200", "CUSTOMER", List.of("ROLE_CUSTOMER"), 3600);
        adminToken = generateToken("999", "ADMIN", List.of("ROLE_ADMIN"), 3600);
        serviceToken = generateToken("transaction-service", "SERVICE", List.of("ROLE_SERVICE"), 300);

        CreateAccountDto createDto = new CreateAccountDto();
        createDto.setCustomerId(100L);
        createDto.setAccountType(Account.AccountType.CURRENT);
        customer1Account = accountService.createAccount(createDto);
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
    void testCreateAccount_BOLA_CustomerCreatingForDifferentCustomerId_Returns403() throws Exception {
        // Customer 100 attempting to create account for Customer 200 -> 403 Forbidden
        String jsonBOLA = "{\"customerId\":200,\"accountType\":\"SAVINGS\"}";
        mockMvc.perform(post("/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBOLA))
                .andExpect(status().isForbidden());

        // Customer 100 creating account for own customerId 100 -> 201 Created
        String jsonOwn = "{\"customerId\":100,\"accountType\":\"SAVINGS\"}";
        mockMvc.perform(post("/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonOwn))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(100));

        // Admin creating account for Customer 200 -> 201 Created
        mockMvc.perform(post("/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBOLA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(200));
    }

    @Test
    void testGetAccount_BOLA_CustomerAccessingOtherAccount_Returns403() throws Exception {
        // Customer 100 accessing own account -> 200 OK
        mockMvc.perform(get("/accounts/" + customer1Account.getAccountNumber())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(customer1Account.getAccountNumber()));

        // Customer 200 accessing Customer 100's account -> 403 Forbidden
        mockMvc.perform(get("/accounts/" + customer1Account.getAccountNumber())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer2Token))
                .andExpect(status().isForbidden());

        // Admin accessing Customer 100's account -> 200 OK
        mockMvc.perform(get("/accounts/" + customer1Account.getAccountNumber())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void testFreezeAndUnfreezeAccount_AdminOnly() throws Exception {
        // Customer attempting freeze -> 403 Forbidden
        mockMvc.perform(post("/accounts/" + customer1Account.getAccountNumber() + "/freeze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                .andExpect(status().isForbidden());

        // Admin attempting freeze -> 200 OK
        mockMvc.perform(post("/accounts/" + customer1Account.getAccountNumber() + "/freeze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Customer attempting unfreeze -> 403 Forbidden
        mockMvc.perform(post("/accounts/" + customer1Account.getAccountNumber() + "/unfreeze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                .andExpect(status().isForbidden());

        // Admin attempting unfreeze -> 200 OK
        mockMvc.perform(post("/accounts/" + customer1Account.getAccountNumber() + "/unfreeze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void testUpdateBalance_StrictServiceRoleProtection() throws Exception {
        String updateJson = "{\"amount\":100.00,\"operationKey\":\"sec-test-op-1\"}";

        // Missing Authorization header -> 401 Unauthorized
        mockMvc.perform(post("/accounts/" + customer1Account.getAccountNumber() + "/update-balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isUnauthorized());

        // Customer token -> 403 Forbidden
        mockMvc.perform(post("/accounts/" + customer1Account.getAccountNumber() + "/update-balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isForbidden());

        // Admin token -> 403 Forbidden
        mockMvc.perform(post("/accounts/" + customer1Account.getAccountNumber() + "/update-balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isForbidden());

        // Valid SERVICE token -> 200 OK
        mockMvc.perform(post("/accounts/" + customer1Account.getAccountNumber() + "/update-balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk());
    }
}
