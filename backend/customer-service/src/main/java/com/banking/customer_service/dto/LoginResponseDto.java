package com.banking.customer_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseDto {
    private String token;
    @Builder.Default
    private String type = "Bearer";
    private Long expiresIn;
    private String role;
    private Long customerId;
}
