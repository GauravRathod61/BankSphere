package com.banking.transaction_service.service;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class ServiceTokenServiceConcurrencyTest {

    @Autowired
    private ServiceTokenService serviceTokenService;

    @Test
    void testConcurrentServiceTokenGeneration_NoCorruptTokensOrRaces() throws Exception {
        int numberOfThreads = 20;
        int operationsPerThread = 50;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numberOfThreads);
        List<String> generatedTokens = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIdx = i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (j % 10 == 0 && threadIdx % 4 == 0) {
                            serviceTokenService.invalidateToken();
                        }
                        String token = serviceTokenService.getServiceToken();
                        assertNotNull(token, "Generated token must not be null");
                        generatedTokens.add(token);

                        // Parse to verify signature/claims integrity
                        SignedJWT signedJWT = SignedJWT.parse(token);
                        assertEquals("transaction-service", signedJWT.getJWTClaimsSet().getSubject());
                        assertEquals("SERVICE", signedJWT.getJWTClaimsSet().getStringClaim("role"));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = finishLatch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        assertTrue(finished, "Concurrency test timed out");
        assertTrue(errors.isEmpty(), "Unexpected errors during concurrent token generation: " + errors);
        assertEquals(numberOfThreads * operationsPerThread, generatedTokens.size());
    }
}
