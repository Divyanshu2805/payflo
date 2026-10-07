package com.project.payflo.api_gateway_service;

import com.project.payflo.test_support.SharedInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Starts the whole gateway with its real settings (read from config-repo) and a real Redis. It needs no config
// server and no Eureka, so it runs the same on a laptop and in CI.
@SpringBootTest
class ApiGatewayServiceApplicationTests {

	@DynamicPropertySource
	static void redis(DynamicPropertyRegistry registry) {
		SharedInfrastructure.registerRedis(registry);
	}

	@Test
	void contextLoads() {
	}

}
