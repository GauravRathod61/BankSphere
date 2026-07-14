package com.banking.transaction_service.dto;

import com.banking.transaction_service.model.Transaction.TransactionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class TransactionRequestDto {
    @NotBlank(message = "Source account number is mandatory")
    private String sourceAccountNumber;

    private String targetAccountNumber;

    @NotNull(message = "Amount is mandatory")
    @Min(value = 1, message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotNull(message = "Transaction type is mandatory")
    private TransactionType type;

    private String description;
}
