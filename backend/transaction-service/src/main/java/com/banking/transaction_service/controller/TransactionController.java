package com.banking.transaction_service.controller;

import com.banking.transaction_service.dto.TransactionRequestDto;
import com.banking.transaction_service.model.Transaction;
import com.banking.transaction_service.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {
    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<Transaction> createTransaction(
            @Valid @RequestBody TransactionRequestDto dto,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return new ResponseEntity<>(transactionService.processTransaction(dto, idempotencyKey), HttpStatus.CREATED);
    }

    @GetMapping("/account/{accountNumber}")
    public ResponseEntity<Page<Transaction>> getTransactionHistory(
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(transactionService.getTransactionHistory(accountNumber, page, size));
    }

    @GetMapping("/mini-statement/{accountNumber}")
    public ResponseEntity<java.util.List<Transaction>> getMiniStatement(@PathVariable String accountNumber) {
        return ResponseEntity.ok(transactionService.getMiniStatement(accountNumber));
    }

    @GetMapping("/monthly-statement/{accountNumber}")
    public ResponseEntity<java.util.List<Transaction>> getMonthlyStatement(
            @PathVariable String accountNumber,
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(transactionService.getMonthlyStatement(accountNumber, year, month));
    }
}
