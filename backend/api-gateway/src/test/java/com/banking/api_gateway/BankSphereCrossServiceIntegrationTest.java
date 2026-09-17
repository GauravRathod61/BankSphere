package com.banking.api_gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BankSphereCrossServiceIntegrationTest {

    private static WireMockServer wireMockServer;

    @LocalServerPort
    private int port;

    private RestClient restClient;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(9099);
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        if (!wireMockServer.isRunning()) {
            wireMockServer.start();
        }
        wireMockServer.resetAll();
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void testEndToEndCustomerLifecycleThroughGateway_WithCorrelationAndSecurity() {
        String clientCorrelationId = "e2e-trace-cycle-9001";
        String mockJwt = "eyJhbGciOiJIUzI1NiJ9.mockJwtPayload.mockSig";

        // Step 1: Customer Registration (POST /customers)
        String customerRegJson = """
                {
                    "firstName": "John",
                    "lastName": "Doe",
                    "email": "john.doe@example.com",
                    "phoneNumber": "1234567890",
                    "password": "Password@123",
                    "address": "123 Main St"
                }
                """;
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/customers"))
                .withHeader("X-Correlation-ID", WireMock.equalTo(clientCorrelationId))
                .willReturn(WireMock.aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Correlation-ID", clientCorrelationId)
                        .withBody("{\"id\":100,\"firstName\":\"John\",\"lastName\":\"Doe\",\"email\":\"john.doe@example.com\",\"role\":\"CUSTOMER\"}")));

        ResponseEntity<String> regResponse = restClient.post()
                .uri("/customers")
                .header("X-Correlation-ID", clientCorrelationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(customerRegJson)
                .retrieve()
                .toEntity(String.class);

        assertEquals(201, regResponse.getStatusCode().value());
        assertEquals(clientCorrelationId, regResponse.getHeaders().getFirst("X-Correlation-ID"));
        assertFalse(regResponse.getBody().contains("passwordHash"));

        // Step 2: Customer Login (POST /auth/login)
        String loginJson = "{\"email\":\"john.doe@example.com\",\"password\":\"Password@123\"}";
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/auth/login"))
                .withHeader("X-Correlation-ID", WireMock.equalTo(clientCorrelationId))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Correlation-ID", clientCorrelationId)
                        .withBody("{\"token\":\"" + mockJwt + "\",\"type\":\"Bearer\",\"expiresIn\":3600,\"role\":\"CUSTOMER\",\"customerId\":100}")));

        ResponseEntity<String> loginResponse = restClient.post()
                .uri("/auth/login")
                .header("X-Correlation-ID", clientCorrelationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(loginJson)
                .retrieve()
                .toEntity(String.class);

        assertEquals(200, loginResponse.getStatusCode().value());
        assertTrue(loginResponse.getBody().contains(mockJwt));

        // Step 3: Account Creation (POST /accounts)
        String accountJson = "{\"customerId\":100,\"accountType\":\"SAVINGS\"}";
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/accounts"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo("Bearer " + mockJwt))
                .withHeader("X-Correlation-ID", WireMock.equalTo(clientCorrelationId))
                .willReturn(WireMock.aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Correlation-ID", clientCorrelationId)
                        .withBody("{\"accountNumber\":\"ACC10001\",\"customerId\":100,\"balance\":0.00,\"accountType\":\"SAVINGS\"}")));

        ResponseEntity<String> accountResponse = restClient.post()
                .uri("/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + mockJwt)
                .header("X-Correlation-ID", clientCorrelationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(accountJson)
                .retrieve()
                .toEntity(String.class);

        assertEquals(201, accountResponse.getStatusCode().value());
        assertTrue(accountResponse.getBody().contains("ACC10001"));

        // Step 4: Process Deposit (POST /transactions) with Idempotency-Key
        String idemKey = "idem-tx-999";
        String txJson = """
                {
                    "sourceAccountNumber": "ACC10001",
                    "amount": 500.00,
                    "type": "DEPOSIT",
                    "description": "Initial account deposit"
                }
                """;
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/transactions"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo("Bearer " + mockJwt))
                .withHeader("Idempotency-Key", WireMock.equalTo(idemKey))
                .withHeader("X-Correlation-ID", WireMock.equalTo(clientCorrelationId))
                .willReturn(WireMock.aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Correlation-ID", clientCorrelationId)
                        .withBody("{\"transactionId\":\"tx-111\",\"sourceAccountNumber\":\"ACC10001\",\"amount\":500.00,\"type\":\"DEPOSIT\",\"status\":\"SUCCESS\"}")));

        ResponseEntity<String> txResponse = restClient.post()
                .uri("/transactions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + mockJwt)
                .header("Idempotency-Key", idemKey)
                .header("X-Correlation-ID", clientCorrelationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(txJson)
                .retrieve()
                .toEntity(String.class);

        assertEquals(201, txResponse.getStatusCode().value());
        assertTrue(txResponse.getBody().contains("SUCCESS"));

        // Step 5: Get Mini-Statement (GET /transactions/mini-statement/ACC10001)
        wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/transactions/mini-statement/ACC10001"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo("Bearer " + mockJwt))
                .withHeader("X-Correlation-ID", WireMock.equalTo(clientCorrelationId))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Correlation-ID", clientCorrelationId)
                        .withBody("[{\"transactionId\":\"tx-111\",\"amount\":500.00,\"type\":\"DEPOSIT\",\"status\":\"SUCCESS\"}]")));

        ResponseEntity<String> statementResponse = restClient.get()
                .uri("/transactions/mini-statement/ACC10001")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + mockJwt)
                .header("X-Correlation-ID", clientCorrelationId)
                .retrieve()
                .toEntity(String.class);

        assertEquals(200, statementResponse.getStatusCode().value());
        assertTrue(statementResponse.getBody().contains("tx-111"));
        assertEquals(clientCorrelationId, statementResponse.getHeaders().getFirst("X-Correlation-ID"));
    }
}
