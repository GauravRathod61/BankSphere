package com.banking.transaction_service.client;

import com.banking.transaction_service.exception.AccountServiceRejectedException;
import com.banking.transaction_service.exception.AccountServiceSecurityException;
import com.banking.transaction_service.exception.AccountServiceTimeoutException;
import com.banking.transaction_service.exception.AccountServiceUnavailableException;
import com.banking.transaction_service.service.ServiceTokenService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AccountServiceClient {

    private static final Pattern ERROR_PATTERN = Pattern.compile("error=\"([^\"]+)\"");
    private static final Pattern ERROR_DESC_PATTERN = Pattern.compile("error_description=\"([^\"]+)\"");

    private final RestClient restClient;
    private final ServiceTokenService serviceTokenService;

    @Value("${banking.account-service-url}")
    private String accountServiceUrl;

    public AccountServiceClient(RestClient restClient, ServiceTokenService serviceTokenService) {
        this.restClient = restClient;
        this.serviceTokenService = serviceTokenService;
    }

    @Retry(name = "accountService", fallbackMethod = "accountServiceFallback")
    @CircuitBreaker(name = "accountService", fallbackMethod = "accountServiceFallback")
    public void updateAccountBalance(String accountNumber, BigDecimal amount, String operationKey) {
        String token = serviceTokenService.getServiceToken();
        try {
            sendUpdateBalanceRequest(accountNumber, amount, operationKey, token);
        } catch (Exception ex) {
            handleRequestException(ex, accountNumber, amount, operationKey, false);
        }
    }

    @Retry(name = "accountService", fallbackMethod = "getAccountOwnerFallback")
    @CircuitBreaker(name = "accountService", fallbackMethod = "getAccountOwnerFallback")
    public Long getAccountOwnerCustomerId(String accountNumber) {
        String token = serviceTokenService.getServiceToken();
        try {
            return sendGetAccountOwnerRequest(accountNumber, token);
        } catch (Exception ex) {
            return handleGetAccountOwnerException(ex, accountNumber, false);
        }
    }

    private Long sendGetAccountOwnerRequest(String accountNumber, String token) {
        String url = accountServiceUrl + "/accounts/" + accountNumber;
        Map<String, Object> account = restClient.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (account != null && account.get("customerId") != null) {
            return Long.valueOf(account.get("customerId").toString());
        }
        return null;
    }

    private Long handleGetAccountOwnerException(Exception ex, String accountNumber, boolean isRetry) {
        if (ex instanceof HttpClientErrorException.NotFound) {
            return null;
        }

        if (ex instanceof HttpClientErrorException.Unauthorized unauthorizedEx) {
            if (!isRetry && isTokenExpiredResponse(unauthorizedEx)) {
                log.warn("SERVICE token expired calling account-service getAccount. Regenerating token and retrying once.");
                serviceTokenService.invalidateToken();
                String freshToken = serviceTokenService.getServiceToken();
                try {
                    return sendGetAccountOwnerRequest(accountNumber, freshToken);
                } catch (Exception retryEx) {
                    return handleGetAccountOwnerException(retryEx, accountNumber, true);
                }
            }
            throw new AccountServiceSecurityException("Account service authentication failed: " + unauthorizedEx.getMessage(), unauthorizedEx);
        }

        if (ex instanceof HttpClientErrorException.Forbidden forbiddenEx) {
            throw new AccountServiceSecurityException("Account service access forbidden: " + forbiddenEx.getMessage(), forbiddenEx);
        }

        if (ex instanceof HttpClientErrorException clientEx) {
            throw new AccountServiceRejectedException("Account service rejected request: " + clientEx.getMessage(), clientEx);
        }

        if (ex instanceof ResourceAccessException rae) {
            if (rae.getCause() instanceof SocketTimeoutException ||
                    (rae.getMessage() != null && rae.getMessage().toLowerCase().contains("read time"))) {
                throw new AccountServiceTimeoutException("Read timeout calling account service", rae);
            }
            throw new AccountServiceUnavailableException("Account service connection failed: " + rae.getMessage(), rae);
        }

        if (ex instanceof RuntimeException re) {
            throw re;
        }
        throw new AccountServiceUnavailableException("Account service call failed: " + ex.getMessage(), ex);
    }

    private void handleRequestException(Exception ex, String accountNumber, BigDecimal amount, String operationKey, boolean isRetry) {
        if (ex instanceof HttpClientErrorException.Unauthorized unauthorizedEx) {
            if (!isRetry && isTokenExpiredResponse(unauthorizedEx)) {
                log.warn("SERVICE token expired calling account-service. Regenerating token and retrying once.");
                serviceTokenService.invalidateToken();
                String freshToken = serviceTokenService.getServiceToken();
                try {
                    sendUpdateBalanceRequest(accountNumber, amount, operationKey, freshToken);
                    return; // Success on single retry
                } catch (Exception retryEx) {
                    handleRequestException(retryEx, accountNumber, amount, operationKey, true);
                    return;
                }
            }
            throw new AccountServiceSecurityException("Account service authentication failed: " + unauthorizedEx.getMessage(), unauthorizedEx);
        }

        if (ex instanceof HttpClientErrorException.Forbidden forbiddenEx) {
            throw new AccountServiceSecurityException("Account service access forbidden: " + forbiddenEx.getMessage(), forbiddenEx);
        }

        if (ex instanceof HttpClientErrorException clientEx) {
            throw new AccountServiceRejectedException("Account service rejected request: " + clientEx.getMessage(), clientEx);
        }

        if (ex instanceof ResourceAccessException rae) {
            if (rae.getCause() instanceof SocketTimeoutException ||
                    (rae.getMessage() != null && rae.getMessage().toLowerCase().contains("read time"))) {
                throw new AccountServiceTimeoutException("Read timeout calling account service", rae);
            }
            throw rae; // Connect exceptions bubble to @Retry / circuit breaker fallback
        }

        if (ex instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(ex);
    }

    private void sendUpdateBalanceRequest(String accountNumber, BigDecimal amount, String operationKey, String token) {
        String url = accountServiceUrl + "/accounts/" + accountNumber + "/update-balance";
        Map<String, Object> request = Map.of("amount", amount, "operationKey", operationKey);
        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private boolean isTokenExpiredResponse(HttpClientErrorException.Unauthorized ex) {
        String authHeader = ex.getResponseHeaders() != null ? ex.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE) : null;
        if (authHeader == null || !authHeader.startsWith("Bearer")) {
            return false;
        }

        Matcher errorMatcher = ERROR_PATTERN.matcher(authHeader);
        Matcher descMatcher = ERROR_DESC_PATTERN.matcher(authHeader);

        if (errorMatcher.find() && descMatcher.find()) {
            String error = errorMatcher.group(1);
            String desc = descMatcher.group(1);
            return "invalid_token".equalsIgnoreCase(error) &&
                    (desc.equalsIgnoreCase("The token is expired") || desc.toLowerCase().startsWith("jwt expired"));
        }
        return false;
    }

    public void accountServiceFallback(String accountNumber, BigDecimal amount, String operationKey, Throwable t) {
        if (t instanceof AccountServiceTimeoutException) {
            throw (AccountServiceTimeoutException) t;
        }
        if (t instanceof AccountServiceRejectedException) {
            throw (AccountServiceRejectedException) t;
        }
        if (t instanceof AccountServiceSecurityException) {
            throw (AccountServiceSecurityException) t;
        }
        throw new AccountServiceUnavailableException("Account service is unavailable: " + t.getMessage(), t);
    }

    public Long getAccountOwnerFallback(String accountNumber, Throwable t) {
        if (t instanceof AccountServiceTimeoutException) {
            throw (AccountServiceTimeoutException) t;
        }
        if (t instanceof AccountServiceRejectedException) {
            throw (AccountServiceRejectedException) t;
        }
        if (t instanceof AccountServiceSecurityException) {
            throw (AccountServiceSecurityException) t;
        }
        throw new AccountServiceUnavailableException("Account service is unavailable: " + t.getMessage(), t);
    }
}
