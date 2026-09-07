package com.banking.transaction_service.config;

import com.banking.transaction_service.filter.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${banking.account-service.connect-timeout:2000ms}")
    private Duration connectTimeout;

    @Value("${banking.account-service.read-timeout:3000ms}")
    private Duration readTimeout;

    @Bean
    public RestClient accountServiceRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
        requestFactory.setReadTimeout((int) readTimeout.toMillis());

        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
                    if (correlationId != null && !correlationId.trim().isEmpty()) {
                        request.getHeaders().set(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
