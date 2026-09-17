package com.banking.customer_service.service;

import com.banking.customer_service.dto.LoginRequestDto;
import com.banking.customer_service.dto.LoginResponseDto;
import com.banking.customer_service.model.Customer;
import com.banking.customer_service.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class CustomerAdminSeedConfigurationTest {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void testAdminSeeding_UsesConfiguredCredentialsAndIsIdempotent() {
        // In application-test.yml, admin password is configured as "TestConfiguredAdminPassword@123"
        String adminEmail = "admin@banksphere.com";
        Optional<Customer> adminOpt = customerRepository.findByEmail(adminEmail);
        assertTrue(adminOpt.isPresent(), "Configured admin user should be seeded on startup");
        assertEquals(Customer.Role.ADMIN, adminOpt.get().getRole());

        // Login succeeds with the configured test password
        LoginRequestDto loginRequest = new LoginRequestDto();
        loginRequest.setEmail(adminEmail);
        loginRequest.setPassword("TestConfiguredAdminPassword@123");

        LoginResponseDto loginResponse = customerService.login(loginRequest);
        assertNotNull(loginResponse);
        assertNotNull(loginResponse.getToken());
        assertEquals("ADMIN", loginResponse.getRole());

        // Login fails with any other or default password
        LoginRequestDto badRequest = new LoginRequestDto();
        badRequest.setEmail(adminEmail);
        badRequest.setPassword("WrongAdminPassword@999");
        assertThrows(BadCredentialsException.class, () -> customerService.login(badRequest));

        // Idempotency: calling seedAdmin() again does not throw or create duplicates
        long countBefore = customerRepository.count();
        customerService.seedAdmin();
        long countAfter = customerRepository.count();
        assertEquals(countBefore, countAfter, "seedAdmin must be idempotent");
    }

    @Test
    void testAdminSeeding_SkippedWhenPasswordBlank() {
        customerService.setAdminCredentials("newadmin@banksphere.com", "");
        customerService.seedAdmin();
        assertTrue(customerRepository.findByEmail("newadmin@banksphere.com").isEmpty(),
                "Admin seeding should be skipped when password is blank or unconfigured");
    }
}
