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

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiGatewayHeaderForwardingTest {

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
    void testAuthorizationHeaderForwarded_ToCustomerService() {
        wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/customers/100"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo("Bearer token-abc-123"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":100,\"name\":\"John Doe\"}")));

        ResponseEntity<String> response = restClient.get()
                .uri("/customers/100")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-abc-123")
                .retrieve()
                .toEntity(String.class);

        assertEquals(200, response.getStatusCode().value());
        wireMockServer.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/customers/100"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo("Bearer token-abc-123")));
    }

    @Test
    void testAuthorizationHeaderForwarded_ToAccountService() {
        wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/accounts/ACC001"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo("Bearer token-xyz-789"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountNumber\":\"ACC001\",\"balance\":1000.00}")));

        ResponseEntity<String> response = restClient.get()
                .uri("/accounts/ACC001")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-xyz-789")
                .retrieve()
                .toEntity(String.class);

        assertEquals(200, response.getStatusCode().value());
        wireMockServer.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/accounts/ACC001"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo("Bearer token-xyz-789")));
    }

    @Test
    void testAuthLoginRouteForwarded_ToAuthService() {
        String loginBody = "{\"email\":\"john@example.com\",\"password\":\"secret123\"}";

        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/auth/login"))
                .withRequestBody(WireMock.equalToJson(loginBody))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"generated-jwt-token\",\"type\":\"Bearer\"}")));

        ResponseEntity<String> response = restClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(loginBody)
                .retrieve()
                .toEntity(String.class);

        assertEquals(200, response.getStatusCode().value());
        wireMockServer.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/auth/login")));
    }

    @Test
    void testAuthorizationHeaderForwarded_ToTransactionService() {
        String transactionBody = "{\"sourceAccountNumber\":\"ACC001\",\"amount\":50.00,\"type\":\"WITHDRAW\"}";

        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/transactions"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo("Bearer token-tx-456"))
                .willReturn(WireMock.aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"transactionId\":\"tx-001\",\"status\":\"SUCCESS\"}")));

        ResponseEntity<String> response = restClient.post()
                .uri("/transactions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-tx-456")
                .contentType(MediaType.APPLICATION_JSON)
                .body(transactionBody)
                .retrieve()
                .toEntity(String.class);

        assertEquals(201, response.getStatusCode().value());
        wireMockServer.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/transactions"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo("Bearer token-tx-456")));
    }
}
