package com.banking.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.function.RouterFunction;

import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
class ApiGatewayApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        Map<String, RouterFunction> routerFunctions = context.getBeansOfType(RouterFunction.class);
        System.out.println("=== RouterFunction beans found: " + routerFunctions.size() + " ===");
        routerFunctions.forEach((name, bean) -> System.out.println("Bean: " + name + " -> " + bean));
    }
}
