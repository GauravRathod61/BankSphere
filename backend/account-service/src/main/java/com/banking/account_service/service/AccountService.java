package com.banking.account_service.service;

import com.banking.account_service.dto.CreateAccountDto;
import com.banking.account_service.model.Account;
import com.banking.account_service.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;

    @Transactional
    public Account createAccount(CreateAccountDto createAccountDto) {
        // Assume Customer API is verified via Gateway/API call in a real scenario
        Account account = new Account();
        account.setCustomerId(createAccountDto.getCustomerId());
        account.setAccountType(createAccountDto.getAccountType());
        account.setAccountNumber(generateAccountNumber());
        return accountRepository.save(account);
    }

    public Account getAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));
    }

    public List<Account> getCustomerAccounts(Long customerId) {
        return accountRepository.findByCustomerId(customerId);
    }

    @Transactional
    public void updateBalance(String accountNumber, BigDecimal amount) {
        Account account = getAccount(accountNumber);
        
        if (account.getStatus() == Account.AccountStatus.FROZEN) {
            throw new RuntimeException("Account is frozen");
        }

        BigDecimal newBalance = account.getBalance().add(amount);
        
        // Minimum Balance Rule for Savings
        if (account.getAccountType() == Account.AccountType.SAVINGS && newBalance.compareTo(new BigDecimal("100")) < 0) {
            throw new RuntimeException("Minimum balance rule violated for SAVINGS account");
        }
        
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("Insufficient funds");
        }
        
        account.setBalance(newBalance);
        accountRepository.save(account);
    }

    @Transactional
    public void freezeAccount(String accountNumber) {
        Account account = getAccount(accountNumber);
        account.setStatus(Account.AccountStatus.FROZEN);
        accountRepository.save(account);
    }

    @Transactional
    public void unfreezeAccount(String accountNumber) {
        Account account = getAccount(accountNumber);
        account.setStatus(Account.AccountStatus.ACTIVE);
        accountRepository.save(account);
    }

    private String generateAccountNumber() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 10).toUpperCase();
    }
}
