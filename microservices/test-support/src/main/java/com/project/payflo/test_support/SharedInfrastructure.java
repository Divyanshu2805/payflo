package com.project.payflo.test_support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL, one Redis and one Kafka for all the integration tests of a module, each started the first time a test
 * needs it and removed when the JVM exits (Testcontainers' Ryuk container does that, even if the build is killed).
 * The services are the real thing against real infrastructure: the point of these tests is what mocks can't show,
 * such as a native query, a unique index, a Flyway migration or Redis's atomic claim.
 */
public final class SharedInfrastructure {

    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> redis;
    private static KafkaContainer kafka;

    private SharedInfrastructure() {
    }

    private static synchronized PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("payflo_it").withUsername("payflo").withPassword("payflo");
            postgres.start();
        }
        return postgres;
    }

    private static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            redis.start();
        }
        return redis;
    }

    private static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));
            kafka.start();
        }
        return kafka;
    }

    /**
     * Points a service's datasource, Redis and Kafka at the containers. (The config server and Eureka are switched
     * off in each module's test application.yaml, because Spring Cloud checks for them before this runs.)
     */
    public static void register(DynamicPropertyRegistry registry) {
        registerRedis(registry);
        PostgreSQLContainer db = postgres();
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
        KafkaContainer broker = kafka();
        registry.add("spring.kafka.bootstrap-servers", broker::getBootstrapServers);
    }

    /** For a service with no database or Kafka, such as the gateway. */
    public static void registerRedis(DynamicPropertyRegistry registry) {
        GenericContainer<?> cache = redis();
        registry.add("spring.data.redis.host", cache::getHost);
        registry.add("spring.data.redis.port", () -> cache.getMappedPort(6379));
    }

    public static String bootstrapServers() {
        return kafka().getBootstrapServers();
    }

    public static String jdbcUrl() {
        return postgres().getJdbcUrl();
    }
}
