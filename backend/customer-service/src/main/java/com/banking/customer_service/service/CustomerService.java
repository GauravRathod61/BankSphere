package com.banking.customer_service.service;

import com.banking.customer_service.dto.CustomerDto;
import com.banking.customer_service.dto.LoginRequestDto;
import com.banking.customer_service.dto.LoginResponseDto;
import com.banking.customer_service.model.Customer;
import com.banking.customer_service.repository.BeneficiaryRepository;
import com.banking.customer_service.repository.CustomerRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {
    private final CustomerRepository customerRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostConstruct
    public void seedAdmin() {
        String adminEmail = "admin@banksphere.com";
        if (customerRepository.findByEmail(adminEmail).isEmpty()) {
            Customer admin = new Customer();
            admin.setFirstName("System");
            admin.setLastName("Admin");
            admin.setEmail(adminEmail);
            admin.setPhoneNumber("0000000000");
            admin.setAddress("BankSphere HQ");
            admin.setPasswordHash(passwordEncoder.encode("Admin@123"));
            admin.setRole(Customer.Role.ADMIN);
            customerRepository.save(admin);
            log.info("Initialized default ADMIN user: {}", adminEmail);
        }
    }

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
        customer.setPasswordHash(passwordEncoder.encode(customerDto.getPassword()));
        customer.setRole(Customer.Role.CUSTOMER);
        
        return customerRepository.save(customer);
    }

    public LoginResponseDto login(LoginRequestDto request) {
        Customer customer = customerRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), customer.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(customer);
        return LoginResponseDto.builder()
                .token(token)
                .type("Bearer")
                .expiresIn(3600L)
                .role(customer.getRole().name())
                .customerId(customer.getId())
                .build();
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
