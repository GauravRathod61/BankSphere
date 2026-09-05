package com.banking.account_service.exception;

public class BalanceUpdateConflictException extends RuntimeException {
    public BalanceUpdateConflictException(String message) {
        super(message);
    }

    public BalanceUpdateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
