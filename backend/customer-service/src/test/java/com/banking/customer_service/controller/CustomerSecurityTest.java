package com.banking.customer_service.controller;

import com.banking.customer_service.dto.CustomerDto;
import com.banking.customer_service.model.Customer;
import com.banking.customer_service.service.CustomerService;
import com.banking.customer_service.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
public class CustomerSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private JwtService jwtService;

    private Customer customer1;
    private Customer customer2;
    private String token1;
    private String token2;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        try {
            CustomerDto c1 = new CustomerDto();
            c1.setFirstName("Bob");
            c1.setLastName("Jones");
            c1.setEmail("bob@example.com");
            c1.setPhoneNumber("1111111111");
            c1.setPassword("Password@123");
            c1.setAddress("111 First St");
            customer1 = customerService.createCustomer(c1);
        } catch (Exception e) {
            customer1 = customerService.getAllCustomers().stream().filter(c -> c.getEmail().equals("bob@example.com")).findFirst().orElseThrow();
        }

        try {
            CustomerDto c2 = new CustomerDto();
            c2.setFirstName("Charlie");
            c2.setLastName("Brown");
            c2.setEmail("charlie@example.com");
            c2.setPhoneNumber("2222222222");
            c2.setPassword("Password@123");
            c2.setAddress("222 Second St");
            customer2 = customerService.createCustomer(c2);
        } catch (Exception e) {
            customer2 = customerService.getAllCustomers().stream().filter(c -> c.getEmail().equals("charlie@example.com")).findFirst().orElseThrow();
        }

        Customer admin = customerService.getAllCustomers().stream().filter(c -> c.getRole() == Customer.Role.ADMIN).findFirst().orElseThrow();

        token1 = jwtService.generateToken(customer1);
        token2 = jwtService.generateToken(customer2);
        adminToken = jwtService.generateToken(admin);
    }

    @Test
    void testPublicRegistration_SucceedsWithoutToken() throws Exception {
        long uniqueTime = System.currentTimeMillis();
        String json = String.format(
                "{\"firstName\":\"David\",\"lastName\":\"Miller\",\"email\":\"david.miller.%d@example.com\",\"phoneNumber\":\"%s\",\"password\":\"Password@123\",\"address\":\"456 Elm St\"}",
                uniqueTime,
                String.valueOf(uniqueTime).substring(0, 10)
        );

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void testGetAllCustomers_CustomerForbidden_AdminAllowed() throws Exception {
        // Customer token -> 403 Forbidden
        mockMvc.perform(get("/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1))
                .andExpect(status().isForbidden());

        // Admin token -> 200 OK
        mockMvc.perform(get("/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void testGetCustomerById_BOLA_CustomerAccessingOtherProfile_Forbidden() throws Exception {
        // Customer 1 accessing Customer 1's profile -> 200 OK
        mockMvc.perform(get("/customers/" + customer1.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customer1.getId()));

        // Customer 1 accessing Customer 2's profile -> 403 Forbidden
        mockMvc.perform(get("/customers/" + customer2.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1))
                .andExpect(status().isForbidden());

        // Admin accessing Customer 2's profile -> 200 OK
        mockMvc.perform(get("/customers/" + customer2.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customer2.getId()));
    }

    @Test
    void testBeneficiaryAuthorization_CustomerAccessingOtherBeneficiary_Forbidden() throws Exception {
        String beneficiaryJson = "{\"name\":\"Beneficiary One\",\"accountNumber\":\"ACC999888\",\"bankName\":\"Test Bank\"}";

        // Customer 1 adds beneficiary to own profile -> 201 Created
        mockMvc.perform(post("/customers/" + customer1.getId() + "/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(beneficiaryJson))
                .andExpect(status().isCreated());

        // Customer 1 attempts to add beneficiary to Customer 2's profile -> 403 Forbidden
        mockMvc.perform(post("/customers/" + customer2.getId() + "/beneficiaries")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(beneficiaryJson))
                .andExpect(status().isForbidden());
    }
}
