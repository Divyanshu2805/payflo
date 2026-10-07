package com.project.payflo.operations_service;

import com.project.payflo.common_lib.enums.ChaosMode;
import com.project.payflo.operations_service.client.AuditServiceClient;
import com.project.payflo.operations_service.client.MerchantServiceClient;
import com.project.payflo.operations_service.client.PaymentServiceClient;
import com.project.payflo.operations_service.settlement.PayoutSimulatorProperties;
import com.project.payflo.test_support.PayfloIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * operations-service on real PostgreSQL, Redis and Kafka, with its two neighbours (merchant-service and
 * payment-service) as stubs. The simulated bank is fast, and its mode can be switched per test, because the simulator's
 * settings are a live bean.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "settlement.simulator.min-delay-seconds=0",
        "settlement.simulator.max-delay-seconds=0",
        "app.webhook.target-cache-ttl-seconds=0"
})
abstract class OperationsIntegrationTest extends PayfloIntegrationTest {

    @MockitoBean
    protected MerchantServiceClient merchantClient;

    @MockitoBean
    protected PaymentServiceClient paymentClient;

    // merchant-service's audit log, which the admin settlement run writes to before it does anything
    @MockitoBean
    protected AuditServiceClient auditClient;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PayoutSimulatorProperties simulator;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @BeforeEach
    void aWellBehavedBankAndClosedCircuitBreakers() {
        // the simulated bank and the recovery job keep running in the background, and would resolve a previous test's
        // leftover settlement in the middle of this one
        jdbc.update("delete from settlement_payment");
        jdbc.update("delete from settlement");
        simulator.setChaosMode(ChaosMode.SUCCESS);
        simulator.setRefuseRate(0);
        // a test that makes a neighbour fail must not leave its breaker open for the next one
        breakers.getAllCircuitBreakers().forEach(breaker -> breaker.reset());
    }
}
