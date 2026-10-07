package com.project.payflo.test_support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for a service's integration tests: the whole application on real infrastructure, hermetic (nothing
 * outside the containers is needed, so it runs the same on a laptop and in CI). The service's own
 * {@code src/test/resources/application.yaml} imports {@code ../config-repo}, so the tests run on the settings the
 * service really uses.
 */
@SpringBootTest
public abstract class PayfloIntegrationTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        SharedInfrastructure.register(registry);
    }
}
