package com.banking.transaction_service.client;

import com.banking.transaction_service.exception.AccountServiceRejectedException;
import com.banking.transaction_service.exception.AccountServiceTimeoutException;
import com.banking.transaction_service.exception.AccountServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
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
    public void updateAccountBalance(String accountNumber, BigDecimal amount, String operationKey) {
        String url = accountServiceUrl + "/accounts/" + accountNumber + "/update-balance";
        Map<String, Object> request = new HashMap<>();
        request.put("amount", amount);
        request.put("operationKey", operationKey);

        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            throw new AccountServiceRejectedException("Account service rejected request: " + e.getMessage(), e);
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException ||
                    (e.getMessage() != null && e.getMessage().toLowerCase().contains("read time"))) {
                throw new AccountServiceTimeoutException("Read timeout calling account service", e);
            }
            throw e;
        }
    }

    public void accountServiceFallback(String accountNumber, BigDecimal amount, String operationKey, Throwable t) {
        if (t instanceof AccountServiceTimeoutException) {
            throw (AccountServiceTimeoutException) t;
        }
        if (t instanceof AccountServiceRejectedException) {
            throw (AccountServiceRejectedException) t;
        }
        throw new AccountServiceUnavailableException("Account service is unavailable: " + t.getMessage(), t);
    }
}
