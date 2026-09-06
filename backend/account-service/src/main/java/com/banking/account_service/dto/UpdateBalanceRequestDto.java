package com.banking.account_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBalanceRequestDto {

    @NotNull(message = "Amount is required")
    private BigDecimal amount;

    @NotBlank(message = "Operation key is required")
    private String operationKey;
}
