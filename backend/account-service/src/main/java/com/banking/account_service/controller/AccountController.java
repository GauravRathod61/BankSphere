package com.banking.account_service.controller;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.dto.UpdateBalanceRequestDto;
import com.banking.account_service.model.Account;
import com.banking.account_service.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final AccountService accountService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or (hasRole('CUSTOMER') and #dto.customerId.toString() == authentication.name)")
    public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountDto dto) {
        return new ResponseEntity<>(accountService.createAccount(dto), HttpStatus.CREATED);
    }

    @GetMapping("/{accountNumber}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CUSTOMER') or hasRole('SERVICE')")
    public ResponseEntity<Account> getAccount(@PathVariable String accountNumber, Authentication authentication) {
        Account account = accountService.getAccount(accountNumber);
        boolean isPrivileged = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_SERVICE"));
        if (!isPrivileged && !account.getCustomerId().toString().equals(authentication.getName())) {
            throw new AccessDeniedException("Access denied to account " + accountNumber);
        }
        return ResponseEntity.ok(account);
    }

    @GetMapping("/customer/{customerId}")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('CUSTOMER') and #customerId.toString() == authentication.name)")
    public ResponseEntity<List<Account>> getCustomerAccounts(@PathVariable Long customerId) {
        return ResponseEntity.ok(accountService.getCustomerAccounts(customerId));
    }

    @PostMapping("/{accountNumber}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> freezeAccount(@PathVariable String accountNumber) {
        accountService.freezeAccount(accountNumber);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{accountNumber}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unfreezeAccount(@PathVariable String accountNumber) {
        accountService.unfreezeAccount(accountNumber);
        return ResponseEntity.ok().build();
    }

    // Internal endpoint for Transaction Service ONLY
    @PostMapping("/{accountNumber}/update-balance")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> updateBalance(@PathVariable String accountNumber, @Valid @RequestBody UpdateBalanceRequestDto dto) {
        accountService.updateBalance(accountNumber, dto.getAmount(), dto.getOperationKey());
        return ResponseEntity.ok().build();
    }
}
