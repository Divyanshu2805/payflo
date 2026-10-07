package com.project.payflo.operations_service;

import com.project.payflo.operations_service.settlement.SettlementIntegrationGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settlement used to be one transaction wrapped around calls to payment-service, merchant-service and the bank, holding a
 * database connection (and row locks) across all of them. It is now a sequence of short transactions
 * ({@code SettlementRecorder}) with the remote calls made between them by an orchestrator that has no transaction.
 *
 * <p>This keeps it that way: a bean that has a transactional method must not hold anything that calls another
 * service, because then the next person to use it from that method would put the call back inside the transaction.
 */
class NoRemoteCallInTransactionTest {

    private static boolean isRemote(Class<?> type) {
        return type.isAnnotationPresent(FeignClient.class)
                || SettlementIntegrationGateway.class.isAssignableFrom(type)
                || type.getSimpleName().endsWith("Gateway")
                || type.getSimpleName().endsWith("Processor") && type.getPackageName().contains("settlement")
                || RestClient.class.isAssignableFrom(type)
                || RestTemplate.class.isAssignableFrom(type)
                || KafkaTemplate.class.isAssignableFrom(type);
    }

    private static boolean hasTransactionalMethod(Class<?> type) {
        if (type.isAnnotationPresent(Transactional.class)) return true;
        return Arrays.stream(type.getDeclaredMethods()).anyMatch(NoRemoteCallInTransactionTest::isTransactional);
    }

    private static boolean isTransactional(Method method) {
        return method.isAnnotationPresent(Transactional.class);
    }

    private static List<Class<?>> beansOfTheService() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class)); // @Service and @Repository are @Components
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.project.payflo.operations_service")) {
            classes.add(Class.forName(definition.getBeanClassName()));
        }
        return classes;
    }

    @Test
    void noBeanWithATransactionalMethodHoldsAClientOfAnotherService() throws Exception {
        List<String> offenders = new ArrayList<>();
        List<Class<?>> transactional = new ArrayList<>();
        for (Class<?> bean : beansOfTheService()) {
            if (!hasTransactionalMethod(bean)) continue;
            transactional.add(bean);
            for (Field field : bean.getDeclaredFields()) {
                if (isRemote(field.getType())) {
                    offenders.add(bean.getSimpleName() + "." + field.getName() + " (" + field.getType().getSimpleName() + ")");
                }
            }
        }

        // the scan has to be finding the settlement steps, or it would pass for the wrong reason
        assertThat(transactional).extracting(Class::getSimpleName).contains("SettlementRecorder", "WebhookDeliveryRecorder");
        assertThat(offenders).as("a remote call would run inside the transaction").isEmpty();
    }

    @Test
    void theSettlementOrchestratorThatMakesTheRemoteCallsHasNoTransactionOfItsOwn() throws Exception {
        for (Class<?> bean : beansOfTheService()) {
            if (bean.getSimpleName().equals("SettlementTransactionExecutor")
                    || bean.getSimpleName().equals("SettlementEngine")
                    || bean.getSimpleName().equals("SettlementRecoveryJob")) {
                assertThat(hasTransactionalMethod(bean))
                        .as(bean.getSimpleName() + " makes remote calls, so it must not be transactional").isFalse();
            }
        }
    }
}
