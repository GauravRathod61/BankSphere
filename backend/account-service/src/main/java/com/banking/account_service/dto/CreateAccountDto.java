package com.banking.account_service.dto;

import com.banking.account_service.model.Account.AccountType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateAccountDto {
    @NotNull(message = "Customer ID is mandatory")
    private Long customerId;

    @NotNull(message = "Account type is mandatory")
    private AccountType accountType;
}
