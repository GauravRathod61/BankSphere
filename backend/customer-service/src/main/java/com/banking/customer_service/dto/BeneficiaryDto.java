package com.banking.customer_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BeneficiaryDto {
    private Long id;
    private Long customerId;
    private String name;
    private String accountNumber;
    private String bankName;
    private LocalDateTime createdAt;
}
