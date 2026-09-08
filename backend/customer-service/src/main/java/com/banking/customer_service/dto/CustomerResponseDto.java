package com.banking.customer_service.dto;

import com.banking.customer_service.model.Customer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerResponseDto {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phoneNumber;
    private String address;
    private Customer.Role role;
    private LocalDateTime createdAt;

    public static CustomerResponseDto fromEntity(Customer customer) {
        if (customer == null) {
            return null;
        }
        return CustomerResponseDto.builder()
                .id(customer.getId())
                .firstName(customer.getFirstName())
                .lastName(customer.getLastName())
                .email(customer.getEmail())
                .phoneNumber(customer.getPhoneNumber())
                .address(customer.getAddress())
                .role(customer.getRole())
                .createdAt(customer.getCreatedAt())
                .build();
    }
}
