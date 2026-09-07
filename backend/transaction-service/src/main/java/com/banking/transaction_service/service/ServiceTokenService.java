package com.banking.transaction_service.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class ServiceTokenService {

    @Value("${banking.security.jwt.secret}")
    private String jwtSecret;

    @Value("${banking.security.jwt.service-token-expiration-seconds:300}")
    private long expirationSeconds;

    private volatile String cachedToken;
    private volatile Instant expiryTime;

    public synchronized String getServiceToken() {
        if (cachedToken != null && expiryTime != null && Instant.now().isBefore(expiryTime.minusSeconds(30))) {
            return cachedToken;
        }
        return generateNewToken();
    }

    public synchronized void invalidateToken() {
        this.cachedToken = null;
        this.expiryTime = null;
    }

    private String generateNewToken() {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expirationSeconds);
        SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        try {
            JWSSigner signer = new MACSigner(key);
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject("transaction-service")
                    .claim("role", "SERVICE")
                    .claim("roles", List.of("ROLE_SERVICE"))
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .build();

            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
            signedJWT.sign(signer);
            this.cachedToken = signedJWT.serialize();
            this.expiryTime = exp;
            return this.cachedToken;
        } catch (JOSEException e) {
            throw new RuntimeException("Failed to generate service token", e);
        }
    }
}
