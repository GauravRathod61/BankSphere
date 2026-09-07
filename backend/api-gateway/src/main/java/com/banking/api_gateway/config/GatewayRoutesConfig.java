package com.banking.api_gateway.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.http.HttpClient;
import java.util.UUID;

import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.path;

@Configuration
public class GatewayRoutesConfig {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Value("${services.customer-service-url:http://localhost:8081}")
    private String customerServiceUrl;

    @Value("${services.account-service-url:http://localhost:8082}")
    private String accountServiceUrl;

    @Value("${services.transaction-service-url:http://localhost:8083}")
    private String transactionServiceUrl;

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return new JdkClientHttpRequestFactory(httpClient);
    }

    @Bean
    public RouterFunction<ServerResponse> customGatewayRoutes() {
        HandlerFilterFunction<ServerResponse, ServerResponse> correlationIdFilter = (request, next) -> {
            String correlationId = request.headers().firstHeader(CORRELATION_ID_HEADER);
            if (correlationId == null || correlationId.trim().isEmpty()) {
                correlationId = UUID.randomUUID().toString();
            }

            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
            final String finalCorrelationId = correlationId;

            ServerRequest modifiedRequest = ServerRequest.from(request)
                    .header(CORRELATION_ID_HEADER, finalCorrelationId)
                    .build();

            try {
                ServerResponse response = next.handle(modifiedRequest);
                response.headers().add(CORRELATION_ID_HEADER, finalCorrelationId);
                return response;
            } finally {
                MDC.remove(CORRELATION_ID_MDC_KEY);
            }
        };

        return route("auth-service")
                .route(path("/auth/**"), http())
                .filter(uri(customerServiceUrl))
                .filter(correlationIdFilter)
                .build()
                .and(route("customer-service")
                        .route(path("/customers/**"), http())
                        .filter(uri(customerServiceUrl))
                        .filter(correlationIdFilter)
                        .build())
                .and(route("account-service")
                        .route(path("/accounts/**"), http())
                        .filter(uri(accountServiceUrl))
                        .filter(correlationIdFilter)
                        .build())
                .and(route("transaction-service")
                        .route(path("/transactions/**"), http())
                        .filter(uri(transactionServiceUrl))
                        .filter(correlationIdFilter)
                        .build());
    }
}
