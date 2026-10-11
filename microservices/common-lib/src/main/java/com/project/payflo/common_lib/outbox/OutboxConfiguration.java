package com.project.payflo.common_lib.outbox;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The transactional outbox, for a service that publishes events: the {@code outbox_event} entity and its repository,
 * the publisher that writes a row inside the caller's transaction, the poller that sends rows to Kafka, and the
 * maintenance jobs. A service opts in with {@code @Import(OutboxConfiguration.class)} on its application class and
 * creates the table in its own migrations; it also needs scheduling, ShedLock and a {@code KafkaTemplate}.
 */
@Configuration(proxyBeanMethods = false)
// Adds this package to the ones Spring Data and Hibernate scan, next to the service's own.
@AutoConfigurationPackage(basePackageClasses = OutboxEvent.class)
@Import({OutboxEventPublisher.class, OutboxPoller.class, OutboxResultHandler.class, OutboxMaintenance.class})
public class OutboxConfiguration {
}
