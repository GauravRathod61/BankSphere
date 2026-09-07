package com.banking.customer_service.controller;

import com.banking.customer_service.dto.CustomerDto;
import com.banking.customer_service.dto.LoginRequestDto;
import com.banking.customer_service.service.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
public class AuthControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        try {
            CustomerDto customer = new CustomerDto();
            customer.setFirstName("Alice");
            customer.setLastName("Smith");
            customer.setEmail("alice@example.com");
            customer.setPhoneNumber("1234567890");
            customer.setPassword("Password@123");
            customer.setAddress("123 Main St");
            customerService.createCustomer(customer);
        } catch (Exception ignored) {
            // Already registered
        }
    }

    @Test
    void testLogin_Success_ReturnsJwtAndDetails() throws Exception {
        String json = "{\"email\":\"alice@example.com\",\"password\":\"Password@123\"}";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.customerId").isNumber());
    }

    @Test
    void testLogin_IdenticalFailureSemantics_NonexistentEmailVsWrongPassword() throws Exception {
        // 1. Attempt login with non-existent email
        String nonexistentJson = "{\"email\":\"unknown@example.com\",\"password\":\"anyPassword123\"}";
        MvcResult resultNonexistent = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nonexistentJson))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // 2. Attempt login with existing email but wrong password
        String wrongPasswordJson = "{\"email\":\"alice@example.com\",\"password\":\"wrongPassword123\"}";
        MvcResult resultWrongPassword = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongPasswordJson))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Assert strictly identical HTTP status code and response payload
        assertEquals(resultNonexistent.getResponse().getStatus(), resultWrongPassword.getResponse().getStatus());
        assertEquals(resultNonexistent.getResponse().getContentAsString(), resultWrongPassword.getResponse().getContentAsString());
    }
}
