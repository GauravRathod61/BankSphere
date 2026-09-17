package com.banking.customer_service.integration;

import com.banking.customer_service.dto.CustomerDto;
import com.banking.customer_service.model.Beneficiary;
import com.banking.customer_service.model.Customer;
import com.banking.customer_service.repository.BeneficiaryRepository;
import com.banking.customer_service.repository.CustomerRepository;
import com.banking.customer_service.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@EnabledIf("isDockerAvailable")
class CustomerPostgreSqlIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_customer_db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (isDockerAvailable() && postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        }
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private BeneficiaryRepository beneficiaryRepository;

    @Test
    void testCustomerAndBeneficiaryPersistenceOnRealPostgreSql() {
        long uniqueTime = System.currentTimeMillis();
        CustomerDto dto = new CustomerDto();
        dto.setFirstName("Alice");
        dto.setLastName("Postgres");
        dto.setEmail("alice." + uniqueTime + "@test.com");
        dto.setPhoneNumber("9" + String.valueOf(uniqueTime).substring(4));
        dto.setPassword("SecurePass@123");
        dto.setAddress("100 PostgreSQL Ave");

        Customer created = customerService.createCustomer(dto);
        assertNotNull(created.getId());
        assertEquals("Alice", created.getFirstName());

        // Test unique constraints on real PostgreSQL
        CustomerDto duplicateEmailDto = new CustomerDto();
        duplicateEmailDto.setFirstName("Bob");
        duplicateEmailDto.setLastName("Duplicate");
        duplicateEmailDto.setEmail(dto.getEmail());
        duplicateEmailDto.setPhoneNumber("8" + String.valueOf(uniqueTime).substring(4));
        duplicateEmailDto.setPassword("SecurePass@123");

        assertThrows(RuntimeException.class, () -> customerService.createCustomer(duplicateEmailDto));

        // Test beneficiary persistence
        com.banking.customer_service.dto.BeneficiaryDto bDto = new com.banking.customer_service.dto.BeneficiaryDto();
        bDto.setName("Charlie");
        bDto.setAccountNumber("ACC987654");
        bDto.setBankName("Partner Bank");

        Beneficiary beneficiary = customerService.addBeneficiary(created.getId(), bDto);
        assertNotNull(beneficiary.getId());
        assertEquals(created.getId(), beneficiary.getCustomerId());

        List<Beneficiary> list = customerService.getBeneficiaries(created.getId());
        assertEquals(1, list.size());
    }
}
