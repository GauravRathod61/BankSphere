package com.banking.account_service.controller;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.model.Account;
import com.banking.account_service.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountDto dto) {
        return new ResponseEntity<>(accountService.createAccount(dto), HttpStatus.CREATED);
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<Account> getAccount(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.getAccount(accountNumber));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<Account>> getCustomerAccounts(@PathVariable Long customerId) {
        return ResponseEntity.ok(accountService.getCustomerAccounts(customerId));
    }

    @PostMapping("/{accountNumber}/freeze")
    public ResponseEntity<Void> freezeAccount(@PathVariable String accountNumber) {
        accountService.freezeAccount(accountNumber);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{accountNumber}/unfreeze")
    public ResponseEntity<Void> unfreezeAccount(@PathVariable String accountNumber) {
        accountService.unfreezeAccount(accountNumber);
        return ResponseEntity.ok().build();
    }

    // Internal endpoint for Transaction Service
    @PostMapping("/{accountNumber}/update-balance")
    public ResponseEntity<Void> updateBalance(@PathVariable String accountNumber, @RequestBody Map<String, BigDecimal> payload) {
        accountService.updateBalance(accountNumber, payload.get("amount"));
        return ResponseEntity.ok().build();
    }
}
