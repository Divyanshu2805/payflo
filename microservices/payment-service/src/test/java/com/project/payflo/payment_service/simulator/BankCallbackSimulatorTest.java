package com.project.payflo.payment_service.simulator;

import com.project.payflo.common_lib.enums.ChaosMode;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.service.AuthorizationResolution;
import com.project.payflo.payment_service.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankCallbackSimulatorTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final SimulatorConfig config = new SimulatorConfig();
    private final BankCallbackSimulator simulator = new BankCallbackSimulator(payments, paymentService, config);

    @BeforeEach
    void everyAnswerIsDueAndApproved() {
        config.setChaosMode(ChaosMode.SUCCESS);
        config.setBatchSize(50);
        config.setConcurrency(2);
    }

    private static Payment payment(LocalDateTime createdAt) {
        Payment payment = Payment.builder().id(UUID.randomUUID()).method(PaymentMethod.UPI)
                .status(PaymentStatus.AUTHORIZING).build();
        payment.setCreatedAt(createdAt);
        return payment;
    }

    private List<Payment> due(int count) {
        return IntStream.range(0, count).mapToObj(i -> payment(LocalDateTime.now().minusHours(1))).toList();
    }

    private void thePaymentsWaiting(List<Payment> waiting) {
        when(payments.findTop500ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(eq(PaymentStatus.AUTHORIZING), any()))
                .thenReturn(waiting);
    }

    @SuppressWarnings("unchecked")
    private List<List<AuthorizationResolution>> resolvedBatches() {
        ArgumentCaptor<List<AuthorizationResolution>> captor = ArgumentCaptor.forClass(List.class);
        verify(paymentService, org.mockito.Mockito.atLeast(0)).resolveAuthorizations(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    @Test
    void everyDuePaymentIsResolvedInBatchesOfTheConfiguredSize() {
        List<Payment> waiting = due(120);
        thePaymentsWaiting(waiting);

        simulator.processCallbacks();

        List<List<AuthorizationResolution>> batches = resolvedBatches();
        assertThat(batches).extracting(List::size).containsExactlyInAnyOrder(50, 50, 20);
        assertThat(batches.stream().flatMap(List::stream).map(AuthorizationResolution::paymentId))
                .containsExactlyInAnyOrderElementsOf(waiting.stream().map(Payment::getId).toList());
        assertThat(batches.stream().flatMap(List::stream)).allSatisfy(answer -> {
            assertThat(answer.approve()).isTrue();
            assertThat(answer.bankRef()).startsWith("SIM_BANK_REF");
        });
        // The one-at-a-time path is only the fallback.
        verify(paymentService, never()).resolveAuthorization(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
    }

    @Test
    void aPaymentThatIsNotDueYetIsLeftAlone() {
        config.getMethods().put("UPI", delay(3600));
        Payment fresh = payment(LocalDateTime.now());
        thePaymentsWaiting(List.of(fresh));

        simulator.processCallbacks();

        verify(paymentService, never()).resolveAuthorizations(anyList());
        verify(paymentService, never()).resolveAuthorization(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
    }

    @Test
    void onlyThePaymentsThatAreDueGoIntoTheBatch() {
        config.getMethods().put("UPI", delay(3600));
        Payment waiting = payment(LocalDateTime.now());
        Payment ready = payment(LocalDateTime.now().minusDays(1));
        thePaymentsWaiting(List.of(waiting, ready));

        simulator.processCallbacks();

        List<List<AuthorizationResolution>> batches = resolvedBatches();
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).extracting(AuthorizationResolution::paymentId).containsExactly(ready.getId());
    }

    @Test
    void aDeclinedPaymentCarriesTheBanksErrorCode() {
        config.setChaosMode(ChaosMode.FAILURE);
        thePaymentsWaiting(due(2));

        simulator.processCallbacks();

        assertThat(resolvedBatches().get(0)).allSatisfy(answer -> {
            assertThat(answer.approve()).isFalse();
            assertThat(answer.errorCode()).isEqualTo("SIM_BANK_ERROR_CODE");
        });
    }

    @Test
    void timeoutModeNeverAnswers() {
        config.setChaosMode(ChaosMode.TIMEOUT);
        thePaymentsWaiting(due(5));

        simulator.processCallbacks();

        verify(paymentService, never()).resolveAuthorizations(anyList());
    }

    @Test
    void aBatchThatFailsIsResolvedOneAtATimeSoOnePaymentCannotHoldTheOthersBack() {
        List<Payment> waiting = due(3);
        thePaymentsWaiting(waiting);
        doThrow(new IllegalStateException("one of them is broken")).when(paymentService).resolveAuthorizations(anyList());
        UUID broken = waiting.get(1).getId();
        doThrow(new IllegalStateException("still broken")).when(paymentService)
                .resolveAuthorization(eq(broken), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());

        simulator.processCallbacks();

        for (Payment payment : waiting) {
            verify(paymentService, times(1)).resolveAuthorization(eq(payment.getId()), eq(true), any(), any(), any());
        }
    }

    private static SimulatorConfig.MethodSimulatorConfig delay(int seconds) {
        SimulatorConfig.MethodSimulatorConfig method = new SimulatorConfig.MethodSimulatorConfig();
        method.setMinDelaySeconds(seconds);
        method.setMaxDelaySeconds(seconds);
        return method;
    }
}
