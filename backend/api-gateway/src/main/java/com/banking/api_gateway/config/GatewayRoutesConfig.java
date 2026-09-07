package com.banking.api_gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.http.HttpClient;

import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.path;

@Configuration
public class GatewayRoutesConfig {

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
        return route("auth-service")
                .route(path("/auth/**"), http())
                .filter(uri(customerServiceUrl))
                .build()
                .and(route("customer-service")
                        .route(path("/customers/**"), http())
                        .filter(uri(customerServiceUrl))
                        .build())
                .and(route("account-service")
                        .route(path("/accounts/**"), http())
                        .filter(uri(accountServiceUrl))
                        .build())
                .and(route("transaction-service")
                        .route(path("/transactions/**"), http())
                        .filter(uri(transactionServiceUrl))
                        .build());
    }
}
