package com.banking.transaction_service.client;

import com.banking.transaction_service.exception.AccountServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
public class AccountServiceClient {

    private final RestClient restClient;

    @Value("${banking.account-service-url}")
    private String accountServiceUrl;

    public AccountServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Retry(name = "accountService", fallbackMethod = "accountServiceFallback")
    @CircuitBreaker(name = "accountService", fallbackMethod = "accountServiceFallback")
    public void updateAccountBalance(String accountNumber, BigDecimal amount) {
        String url = accountServiceUrl + "/accounts/" + accountNumber + "/update-balance";
        Map<String, BigDecimal> request = new HashMap<>();
        request.put("amount", amount);

        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    public void accountServiceFallback(String accountNumber, BigDecimal amount, Throwable t) {
        throw new AccountServiceUnavailableException("Account service is unavailable: " + t.getMessage(), t);
    }
}
