package com.banking.transaction_service.controller;

import com.banking.transaction_service.client.AccountServiceClient;
import com.banking.transaction_service.dto.TransactionRequestDto;
import com.banking.transaction_service.model.Transaction;
import com.banking.transaction_service.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final AccountServiceClient accountServiceClient;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('CUSTOMER')")
    public ResponseEntity<Transaction> createTransaction(
            @Valid @RequestBody TransactionRequestDto dto,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        
        boolean isAdmin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin) {
            // For WITHDRAW and TRANSFER, verify that caller is the owner of the source account
            if (dto.getType() == Transaction.TransactionType.WITHDRAW || dto.getType() == Transaction.TransactionType.TRANSFER) {
                verifyAccountOwnership(dto.getSourceAccountNumber(), authentication.getName());
            }
        }
        return new ResponseEntity<>(transactionService.processTransaction(dto, idempotencyKey), HttpStatus.CREATED);
    }

    @GetMapping("/account/{accountNumber}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CUSTOMER')")
    public ResponseEntity<Page<Transaction>> getTransactionHistory(
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        
        verifyAccess(accountNumber, authentication);
        return ResponseEntity.ok(transactionService.getTransactionHistory(accountNumber, page, size));
    }

    @GetMapping("/mini-statement/{accountNumber}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CUSTOMER')")
    public ResponseEntity<List<Transaction>> getMiniStatement(
            @PathVariable String accountNumber,
            Authentication authentication) {
        
        verifyAccess(accountNumber, authentication);
        return ResponseEntity.ok(transactionService.getMiniStatement(accountNumber));
    }

    @GetMapping("/monthly-statement/{accountNumber}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CUSTOMER')")
    public ResponseEntity<List<Transaction>> getMonthlyStatement(
            @PathVariable String accountNumber,
            @RequestParam int year,
            @RequestParam int month,
            Authentication authentication) {
        
        verifyAccess(accountNumber, authentication);
        return ResponseEntity.ok(transactionService.getMonthlyStatement(accountNumber, year, month));
    }

    private void verifyAccess(String accountNumber, Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin) {
            verifyAccountOwnership(accountNumber, authentication.getName());
        }
    }

    private void verifyAccountOwnership(String accountNumber, String authenticatedCustomerId) {
        Long ownerCustomerId = accountServiceClient.getAccountOwnerCustomerId(accountNumber);
        if (ownerCustomerId == null || !ownerCustomerId.toString().equals(authenticatedCustomerId)) {
            throw new AccessDeniedException("Access denied: You do not own account " + accountNumber);
        }
    }
}
