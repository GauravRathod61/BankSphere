package com.banking.account_service.controller;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
public class AccountSecurityClockSkewTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Value("${banking.security.jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private String generateTokenWithSkew(long skewSecondsInPast) throws Exception {
        // Skewed token that expired `skewSecondsInPast` seconds ago
        Instant now = Instant.now();
        Instant exp = now.minusSeconds(skewSecondsInPast);
        Instant iat = exp.minusSeconds(300);

        SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWSSigner signer = new MACSigner(key);
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject("100")
                .claim("role", "CUSTOMER")
                .claim("roles", List.of("ROLE_CUSTOMER"))
                .issueTime(Date.from(iat))
                .expirationTime(Date.from(exp))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        signedJWT.sign(signer);
        return signedJWT.serialize();
    }

    @Test
    void testTokenWithin60SecondClockSkew_IsAccepted() throws Exception {
        // Token expired 30 seconds ago -> within 60-second clock skew tolerance
        String token30sSkew = generateTokenWithSkew(30);

        // GET /accounts/customer/100 should authenticate successfully and return 200 (even if empty list)
        mockMvc.perform(get("/accounts/customer/100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token30sSkew))
                .andExpect(status().isOk());
    }

    @Test
    void testTokenBeyond60SecondClockSkew_IsRejected() throws Exception {
        // Token expired 90 seconds ago -> beyond 60-second clock skew tolerance
        String token90sSkew = generateTokenWithSkew(90);

        // Expect 401 Unauthorized
        mockMvc.perform(get("/accounts/customer/100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token90sSkew))
                .andExpect(status().isUnauthorized());
    }
}
