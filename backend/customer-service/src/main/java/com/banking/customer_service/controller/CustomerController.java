package com.banking.customer_service.controller;

import com.banking.customer_service.dto.BeneficiaryDto;
import com.banking.customer_service.dto.CustomerDto;
import com.banking.customer_service.model.Beneficiary;
import com.banking.customer_service.model.Customer;
import com.banking.customer_service.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasRole('ADMIN') or (hasRole('CUSTOMER') and #id.toString() == authentication.name)")
    public ResponseEntity<Customer> getCustomer(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getCustomerById(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Customer>> getAllCustomers() {
        return ResponseEntity.ok(customerService.getAllCustomers());
    }

    @PostMapping("/{customerId}/beneficiaries")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('CUSTOMER') and #customerId.toString() == authentication.name)")
    public ResponseEntity<Beneficiary> addBeneficiary(
            @PathVariable Long customerId,
            @Valid @RequestBody BeneficiaryDto dto) {
        return new ResponseEntity<>(customerService.addBeneficiary(customerId, dto), HttpStatus.CREATED);
    }

    @GetMapping("/{customerId}/beneficiaries")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('CUSTOMER') and #customerId.toString() == authentication.name)")
    public ResponseEntity<List<Beneficiary>> getBeneficiaries(@PathVariable Long customerId) {
        return ResponseEntity.ok(customerService.getBeneficiaries(customerId));
    }

    @DeleteMapping("/{customerId}/beneficiaries/{beneficiaryId}")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('CUSTOMER') and #customerId.toString() == authentication.name)")
    public ResponseEntity<Void> deleteBeneficiary(
            @PathVariable Long customerId,
            @PathVariable Long beneficiaryId) {
        customerService.deleteBeneficiary(customerId, beneficiaryId);
        return ResponseEntity.noContent().build();
    }
}
