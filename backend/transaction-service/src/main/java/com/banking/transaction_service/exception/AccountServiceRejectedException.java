package com.banking.transaction_service.exception;

public class AccountServiceRejectedException extends RuntimeException {
    public AccountServiceRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
