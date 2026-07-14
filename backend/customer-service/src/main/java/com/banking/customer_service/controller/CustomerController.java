package com.banking.customer_service.controller;

import com.banking.customer_service.dto.CustomerDto;
import com.banking.customer_service.model.Customer;
import com.banking.customer_service.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService customerService;

    @PostMapping
    public ResponseEntity<Customer> createCustomer(@Valid @RequestBody CustomerDto customerDto) {
        return new ResponseEntity<>(customerService.createCustomer(customerDto), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Customer> getCustomer(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getCustomerById(id));
    }

    @GetMapping
    public ResponseEntity<List<Customer>> getAllCustomers() {
        return ResponseEntity.ok(customerService.getAllCustomers());
    }

    @PostMapping("/{customerId}/beneficiaries")
    public ResponseEntity<com.banking.customer_service.model.Beneficiary> addBeneficiary(
            @PathVariable Long customerId,
            @Valid @RequestBody com.banking.customer_service.dto.BeneficiaryDto dto) {
        return new ResponseEntity<>(customerService.addBeneficiary(customerId, dto), HttpStatus.CREATED);
    }

    @GetMapping("/{customerId}/beneficiaries")
    public ResponseEntity<List<com.banking.customer_service.model.Beneficiary>> getBeneficiaries(@PathVariable Long customerId) {
        return ResponseEntity.ok(customerService.getBeneficiaries(customerId));
    }

    @DeleteMapping("/{customerId}/beneficiaries/{beneficiaryId}")
    public ResponseEntity<Void> deleteBeneficiary(
            @PathVariable Long customerId,
            @PathVariable Long beneficiaryId) {
        customerService.deleteBeneficiary(customerId, beneficiaryId);
        return ResponseEntity.noContent().build();
    }
}
