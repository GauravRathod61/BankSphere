package com.banking.transaction_service.exception;

public class AccountServiceTimeoutException extends RuntimeException {
    public AccountServiceTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
