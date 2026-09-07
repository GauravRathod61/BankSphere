package com.banking.transaction_service.exception;

public class AccountServiceSecurityException extends RuntimeException {
    public AccountServiceSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
