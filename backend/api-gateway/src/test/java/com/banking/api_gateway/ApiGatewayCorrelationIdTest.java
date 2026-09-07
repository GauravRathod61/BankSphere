package com.banking.api_gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiGatewayCorrelationIdTest {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
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
    void testGeneratedCorrelationId_WhenHeaderMissing() {
        wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/customers/100"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":100,\"name\":\"Jane Doe\"}")));

        ResponseEntity<String> response = restClient.get()
                .uri("/customers/100")
                .retrieve()
                .toEntity(String.class);

        assertEquals(200, response.getStatusCode().value());
        String responseCorrId = response.getHeaders().getFirst(CORRELATION_ID_HEADER);
        assertNotNull(responseCorrId);
        assertTrue(responseCorrId.matches("^[0-9a-fA-F-]{36}$"), "Generated correlation ID should be a valid UUID");

        // Verify downstream WireMock received the EXACT same generated correlation ID
        wireMockServer.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/customers/100"))
                .withHeader(CORRELATION_ID_HEADER, WireMock.equalTo(responseCorrId)));
    }

    @Test
    void testPreservedCorrelationId_WhenHeaderPresent() {
        String clientCorrId = "client-trace-id-abc-123";

        wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/accounts/ACC001"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountNumber\":\"ACC001\",\"balance\":2000.00}")));

        ResponseEntity<String> response = restClient.get()
                .uri("/accounts/ACC001")
                .header(CORRELATION_ID_HEADER, clientCorrId)
                .retrieve()
                .toEntity(String.class);

        assertEquals(200, response.getStatusCode().value());
        String responseCorrId = response.getHeaders().getFirst(CORRELATION_ID_HEADER);
        assertEquals(clientCorrId, responseCorrId);

        // Verify downstream WireMock received the exact preserved correlation ID
        wireMockServer.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/accounts/ACC001"))
                .withHeader(CORRELATION_ID_HEADER, WireMock.equalTo(clientCorrId)));
    }
}
