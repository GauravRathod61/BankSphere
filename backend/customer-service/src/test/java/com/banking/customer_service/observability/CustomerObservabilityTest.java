package com.banking.customer_service.observability;

import com.banking.customer_service.filter.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class CustomerObservabilityTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void testCorrelationIdEcho_AndMdcLifecycle_NormalCompletion() throws Exception {
        String clientCorrId = "custom-cust-corr-12345";
        String customerJson = "{\"firstName\":\"Alice\",\"lastName\":\"Smith\",\"email\":\"alice.obs@example.com\",\"password\":\"Pass@123\",\"phoneNumber\":\"1122334455\",\"address\":\"123 Street\"}";

        mockMvc.perform(post("/customers")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, clientCorrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson))
                .andExpect(status().isCreated())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, clientCorrId));

        // Verify MDC is cleared after normal completion
        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), "MDC must be cleared after normal completion");
    }

    @Test
    void testCorrelationIdGenerated_WhenMissing_AndMdcLifecycle_ExceptionPath() throws Exception {
        String badLoginJson = "{\"email\":\"nonexistent@example.com\",\"password\":\"wrongpassword\"}";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badLoginJson))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));

        // Verify MDC is cleared after exception path
        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), "MDC must be cleared after exception path");
    }

    @Test
    void testBusinessMetricsIncrement() throws Exception {
        double initialReg = getCounterCount("banking.customers.registered", "status", "SUCCESS");
        double initialLoginFail = getCounterCount("banking.auth.logins", "status", "FAILED");
        double initialLoginSuccess = getCounterCount("banking.auth.logins", "status", "SUCCESS");

        // 1. Register customer
        String regJson = "{\"firstName\":\"Bob\",\"lastName\":\"Jones\",\"email\":\"bob.obs@example.com\",\"password\":\"BobPass@123\",\"phoneNumber\":\"9988776655\",\"address\":\"456 Avenue\"}";
        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isCreated());

        assertEquals(initialReg + 1, getCounterCount("banking.customers.registered", "status", "SUCCESS"));

        // 2. Failed login
        String failLoginJson = "{\"email\":\"bob.obs@example.com\",\"password\":\"WrongPass\"}";
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failLoginJson))
                .andExpect(status().isUnauthorized());

        assertEquals(initialLoginFail + 1, getCounterCount("banking.auth.logins", "status", "FAILED"));

        // 3. Successful login
        String successLoginJson = "{\"email\":\"bob.obs@example.com\",\"password\":\"BobPass@123\"}";
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successLoginJson))
                .andExpect(status().isOk());

        assertEquals(initialLoginSuccess + 1, getCounterCount("banking.auth.logins", "status", "SUCCESS"));
    }

    private double getCounterCount(String name, String tagKey, String tagValue) {
        var counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter != null ? counter.count() : 0.0;
    }
}
