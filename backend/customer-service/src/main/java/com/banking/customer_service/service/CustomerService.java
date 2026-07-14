package com.banking.customer_service.service;

import com.banking.customer_service.dto.CustomerDto;
import com.banking.customer_service.model.Customer;
import com.banking.customer_service.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerService {
    private final CustomerRepository customerRepository;
    private final com.banking.customer_service.repository.BeneficiaryRepository beneficiaryRepository;

    public Customer createCustomer(CustomerDto customerDto) {
        if (customerRepository.findByEmail(customerDto.getEmail()).isPresent()) {
            throw new RuntimeException("Email already registered");
        }
        if (customerRepository.findByPhoneNumber(customerDto.getPhoneNumber()).isPresent()) {
            throw new RuntimeException("Phone number already registered");
        }

        Customer customer = new Customer();
        customer.setFirstName(customerDto.getFirstName());
        customer.setLastName(customerDto.getLastName());
        customer.setEmail(customerDto.getEmail());
        customer.setPhoneNumber(customerDto.getPhoneNumber());
        customer.setAddress(customerDto.getAddress());
        
        return customerRepository.save(customer);
    }

    public Customer getCustomerById(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Customer not found"));
    }

    public List<Customer> getAllCustomers() {
        return customerRepository.findAll();
    }

    public com.banking.customer_service.model.Beneficiary addBeneficiary(Long customerId, com.banking.customer_service.dto.BeneficiaryDto dto) {
        Customer customer = getCustomerById(customerId);
        com.banking.customer_service.model.Beneficiary beneficiary = com.banking.customer_service.model.Beneficiary.builder()
                .customerId(customer.getId())
                .name(dto.getName())
                .accountNumber(dto.getAccountNumber())
                .bankName(dto.getBankName())
                .build();
        return beneficiaryRepository.save(beneficiary);
    }

    public List<com.banking.customer_service.model.Beneficiary> getBeneficiaries(Long customerId) {
        return beneficiaryRepository.findByCustomerId(customerId);
    }

    public void deleteBeneficiary(Long customerId, Long beneficiaryId) {
        com.banking.customer_service.model.Beneficiary beneficiary = beneficiaryRepository.findById(beneficiaryId)
                .orElseThrow(() -> new RuntimeException("Beneficiary not found"));
        if (!beneficiary.getCustomerId().equals(customerId)) {
            throw new RuntimeException("Beneficiary does not belong to this customer");
        }
        beneficiaryRepository.delete(beneficiary);
    }
}
