package com.project.payflo.payment_service.service;

import com.project.payflo.payment_service.dto.response.PaymentResponse;
import com.project.payflo.common_lib.exception.IdempotencyKeyReusedException;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.payment_service.dto.request.PaymentInitRequest;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.gateway.PaymentGatewayRouter;
import com.project.payflo.payment_service.gateway.dto.PaymentResult;
import com.project.payflo.payment_service.mapper.PaymentMapper;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.OrderRepository;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.saga.PaymentAuthorizationRecorder;
import com.project.payflo.payment_service.service.impl.PaymentServiceImpl;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import com.project.payflo.payment_service.velocity.CardVelocityGuard;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceImplTest {

    private final PaymentAuthorizationRecorder recorder = mock(PaymentAuthorizationRecorder.class);
    private final PaymentGatewayRouter router = mock(PaymentGatewayRouter.class);
    private final CardVelocityGuard cardGuard = mock(CardVelocityGuard.class);

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentTransitionService transitions = mock(PaymentTransitionService.class);

    private final OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);

    private final PaymentServiceImpl service = new PaymentServiceImpl(orderRepository,
            paymentRepository, router, mock(PaymentMapper.class), transitions,
            publisher, recorder, cardGuard);

    private final UUID merchantId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void aRecordedPayment() {
        Payment payment = Payment.builder().id(paymentId).amount(Money.inr(1000)).method(PaymentMethod.CARD).build();
        when(recorder.recordPayment(any(), any(), any())).thenReturn(payment);
    }

    private static PaymentInitRequest request(UUID orderId, PaymentMethod method, Map<String, Object> details) {
        return new PaymentInitRequest(orderId, method, details);
    }

    private void assertRejected(PaymentInitRequest request, String errorCode) {
        assertThatThrownBy(() -> service.initiate(merchantId, request, null))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
        // rejected before anything was written or any processor was called
        verify(recorder, never()).recordPayment(any(), any(), any());
        verify(router, never()).initiate(any());
    }

    // ---- request validation

    @Test
    void aCardPaymentNeedsATokenString() {
        assertRejected(request(orderId, PaymentMethod.CARD, null), "INVALID_PAYMENT_DETAILS");
        assertRejected(request(orderId, PaymentMethod.CARD, Map.of()), "INVALID_PAYMENT_DETAILS");
        assertRejected(request(orderId, PaymentMethod.CARD, Map.of("token", " ")), "INVALID_PAYMENT_DETAILS");
        assertRejected(request(orderId, PaymentMethod.CARD, Map.of("token", 12345)), "INVALID_PAYMENT_DETAILS");
    }

    @Test
    void aUpiPaymentNeedsAVpaAndANetBankingPaymentABank() {
        assertRejected(request(orderId, PaymentMethod.UPI, Map.of("bank", "HDFC")), "INVALID_PAYMENT_DETAILS");
        assertRejected(request(orderId, PaymentMethod.NETBANKING, Map.of("vpa", "a@b")), "INVALID_PAYMENT_DETAILS");
    }

    @Test
    void aWalletPaymentNeedsTheWalletName() {
        assertRejected(request(orderId, PaymentMethod.WALLET, Map.of("anything", "x")), "INVALID_PAYMENT_DETAILS");
        assertRejected(request(orderId, PaymentMethod.WALLET, null), "INVALID_PAYMENT_DETAILS");
    }

    @Test
    void aWalletPaymentWithAWalletNameIsAccepted() {
        when(router.initiate(any())).thenReturn(new PaymentResult.Pending("WALLET_PROCESSOR_x"));

        service.initiate(merchantId, request(orderId, PaymentMethod.WALLET, Map.of("wallet", "PAYTM")), null);

        verify(recorder).recordPayment(any(), any(), any());
        verify(recorder).applyGatewayResult(eq(paymentId), any(PaymentResult.Pending.class));
    }

    @Test
    void aTokenThatIsTooLongIsRejected() {
        Map<String, Object> details = new HashMap<>();
        details.put("token", "t".repeat(201));
        assertRejected(request(orderId, PaymentMethod.CARD, details), "INVALID_PAYMENT_DETAILS");
    }

    // ---- card-testing protection

    @Test
    void aCardPaymentIsCheckedBeforeAnythingIsRecordedAndCountedOnceItIs() {
        when(router.initiate(any())).thenReturn(new PaymentResult.Pending("CARD_PROCESSOR_x"));

        service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), null);

        var inOrder = org.mockito.Mockito.inOrder(cardGuard, recorder);
        inOrder.verify(cardGuard).requireAllowed(merchantId);
        inOrder.verify(recorder).recordPayment(any(), any(), any());
        inOrder.verify(cardGuard).recordAttempt(merchantId);
    }

    @Test
    void aMerchantRefusedForCardTestingHasNoPaymentRecordedAndNoProcessorCalled() {
        org.mockito.Mockito.doThrow(new com.project.payflo.common_lib.exception.VelocityLimitException(
                "CARD_TESTING_SUSPECTED", "too many declines", 300)).when(cardGuard).requireAllowed(merchantId);

        assertThatThrownBy(() -> service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), null))
                .isInstanceOf(com.project.payflo.common_lib.exception.VelocityLimitException.class);

        verify(recorder, never()).recordPayment(any(), any(), any());
        verify(router, never()).initiate(any());
        verify(cardGuard, never()).recordAttempt(any());
    }

    @Test
    void otherMethodsNeverTouchTheCardGuard() {
        when(router.initiate(any())).thenReturn(new PaymentResult.Pending("UPI_PROCESSOR_x"));

        service.initiate(merchantId, request(orderId, PaymentMethod.UPI, Map.of("vpa", "a@b")), null);

        verify(cardGuard, never()).requireAllowed(any());
        verify(cardGuard, never()).recordAttempt(any());
    }

    @Test
    void aRepeatedRequestIsAReplayAndCountsAsNothing() {
        var existing = new PaymentResponse(paymentId, orderId, merchantId, Money.inr(1000), PaymentStatus.AUTHORIZING,
                PaymentMethod.CARD, null, null, null, null, null);
        when(recorder.findExistingAttempt(merchantId, "key-1")).thenReturn(Optional.of(existing));

        service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), "key-1");

        verify(cardGuard, never()).requireAllowed(any());
        verify(cardGuard, never()).recordAttempt(any());
    }

    @Test
    void aBankDeclineOfACardIsCountedTowardsCardTestingButADeclinedUpiPaymentIsNot() {
        OrderRecord order = OrderRecord.builder().id(orderId).orderStatus(OrderStatus.ATTEMPTED).build();
        Payment card = Payment.builder().id(paymentId).order(order).merchantId(merchantId).amount(Money.inr(1000))
                .method(PaymentMethod.CARD).status(PaymentStatus.AUTHORIZING).build();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(card));

        service.resolveAuthorization(paymentId, false, null, "SIM_BANK_ERROR_CODE", "Simulated Bank Decline");

        verify(cardGuard).recordDecline(merchantId, "SIM_BANK_ERROR_CODE");

        UUID upiId = UUID.randomUUID();
        Payment upi = Payment.builder().id(upiId).order(order).merchantId(merchantId).amount(Money.inr(1000))
                .method(PaymentMethod.UPI).status(PaymentStatus.AUTHORIZING).build();
        when(paymentRepository.findByIdForUpdate(upiId)).thenReturn(Optional.of(upi));

        service.resolveAuthorization(upiId, false, null, "SIM_BANK_ERROR_CODE", "Simulated Bank Decline");

        verify(cardGuard, org.mockito.Mockito.times(1)).recordDecline(any(), anyString());
    }

    // ---- resolving a batch of authorizations in one transaction

    private Payment waiting(PaymentMethod method, PaymentStatus status) {
        OrderRecord order = OrderRecord.builder().id(UUID.randomUUID()).orderStatus(OrderStatus.ATTEMPTED).build();
        return Payment.builder().id(UUID.randomUUID()).order(order).merchantId(merchantId).amount(Money.inr(1000))
                .method(method).status(status).build();
    }

    @Test
    void aBatchLocksItsPaymentsAndLoadsTheirOrdersInOneQueryEachAndCapturesEveryApprovedOne() {
        Payment a = waiting(PaymentMethod.UPI, PaymentStatus.AUTHORIZING);
        Payment b = waiting(PaymentMethod.CARD, PaymentStatus.AUTHORIZING);
        when(paymentRepository.findAllByIdForUpdate(any())).thenReturn(List.of(a, b));
        when(router.capture(any())).thenReturn(new PaymentResult.Success("REF"));

        service.resolveAuthorizations(List.of(
                AuthorizationResolution.approved(a.getId(), "REF_A"), AuthorizationResolution.approved(b.getId(), "REF_B")));

        verify(paymentRepository, org.mockito.Mockito.times(1)).findAllByIdForUpdate(any());
        verify(orderRepository, org.mockito.Mockito.times(1)).findAllById(any());
        verify(paymentRepository, never()).findByIdForUpdate(any());
        for (Payment payment : List.of(a, b)) {
            var inOrder = org.mockito.Mockito.inOrder(transitions);
            inOrder.verify(transitions).apply(payment, PaymentEvent.AUTHORIZE_SUCCESS);
            inOrder.verify(transitions).apply(payment, PaymentEvent.CAPTURE_REQUEST);
            inOrder.verify(transitions).apply(payment, PaymentEvent.CAPTURE_SUCCESS);
            assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.PAID);
        }
        assertThat(a.getBankReference()).isEqualTo("REF_A");
        assertThat(b.getBankReference()).isEqualTo("REF_B");
        verify(publisher, org.mockito.Mockito.times(2))
                .publish(eq(com.project.payflo.common_lib.enums.EventAggregateType.PAYMENT), any(), eq("PAYMENT_STATUS_CHANGED"), any());
    }

    @Test
    void aPaymentThatIsNoLongerWaitingForTheBankIsSkippedWithoutAnEventOrATransition() {
        Payment alreadyDone = waiting(PaymentMethod.UPI, PaymentStatus.CAPTURED);
        Payment stillWaiting = waiting(PaymentMethod.UPI, PaymentStatus.AUTHORIZING);
        when(paymentRepository.findAllByIdForUpdate(any())).thenReturn(List.of(alreadyDone, stillWaiting));
        when(router.capture(any())).thenReturn(new PaymentResult.Success("REF"));

        service.resolveAuthorizations(List.of(AuthorizationResolution.approved(alreadyDone.getId(), "X"),
                AuthorizationResolution.approved(stillWaiting.getId(), "Y"),
                // a payment that can't be found is skipped too, not an error for the rest of the batch
                AuthorizationResolution.approved(UUID.randomUUID(), "Z")));

        verify(transitions, never()).apply(eq(alreadyDone), any());
        verify(transitions).apply(stillWaiting, PaymentEvent.AUTHORIZE_SUCCESS);
        verify(publisher, org.mockito.Mockito.times(1)).publish(any(), eq(stillWaiting.getId()), anyString(), any());
    }

    @Test
    void aRefusedCaptureInABatchLeavesThePaymentAuthorizedWithTheBanksError() {
        Payment payment = waiting(PaymentMethod.UPI, PaymentStatus.AUTHORIZING);
        when(paymentRepository.findAllByIdForUpdate(any())).thenReturn(List.of(payment));
        when(router.capture(payment)).thenReturn(new PaymentResult.Failure("CAPTURE_DECLINED", "refused"));

        service.resolveAuthorizations(List.of(AuthorizationResolution.approved(payment.getId(), "REF")));

        verify(transitions).apply(payment, PaymentEvent.CAPTURE_FAIL);
        assertThat(payment.getErrorCode()).isEqualTo("CAPTURE_DECLINED");
        assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.ATTEMPTED);
    }

    @Test
    void aDeclinedCardInABatchIsCountedForCardTestingOnlyAfterTheCommit() {
        Payment card = waiting(PaymentMethod.CARD, PaymentStatus.AUTHORIZING);
        Payment upi = waiting(PaymentMethod.UPI, PaymentStatus.AUTHORIZING);
        when(paymentRepository.findAllByIdForUpdate(any())).thenReturn(List.of(card, upi));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.resolveAuthorizations(List.of(
                    AuthorizationResolution.declined(card.getId(), "SIM_BANK_ERROR_CODE", "Simulated Bank Decline"),
                    AuthorizationResolution.declined(upi.getId(), "SIM_BANK_ERROR_CODE", "Simulated Bank Decline")));

            // still inside the transaction: nothing has gone to Redis yet
            verify(cardGuard, never()).recordDecline(any(), anyString());

            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        verify(transitions).apply(card, PaymentEvent.AUTHORIZE_FAIL);
        verify(transitions).apply(upi, PaymentEvent.AUTHORIZE_FAIL);
        verify(cardGuard, org.mockito.Mockito.times(1)).recordDecline(merchantId, "SIM_BANK_ERROR_CODE");
    }

    @Test
    void anEmptyBatchTouchesNothing() {
        service.resolveAuthorizations(List.of());

        verify(paymentRepository, never()).findAllByIdForUpdate(any());
    }

    // ---- manual capture

    private Payment authorizedPayment(OrderRecord order) {
        Payment authorized = Payment.builder().id(paymentId).order(order).merchantId(merchantId)
                .amount(Money.inr(1000)).method(PaymentMethod.CARD).status(PaymentStatus.AUTHORIZED).build();
        when(paymentRepository.findByIdAndMerchantIdForUpdate(paymentId, merchantId)).thenReturn(Optional.of(authorized));
        when(paymentRepository.save(authorized)).thenReturn(authorized);
        return authorized;
    }

    @Test
    void aSuccessfulManualCaptureMarksTheOrderPaid() {
        OrderRecord order = OrderRecord.builder().id(orderId).orderStatus(OrderStatus.ATTEMPTED).build();
        Payment authorized = authorizedPayment(order);
        when(router.capture(authorized)).thenReturn(new PaymentResult.Success("REF"));

        service.capture(merchantId, paymentId);

        verify(transitions).apply(authorized, PaymentEvent.CAPTURE_SUCCESS);
        assertThat(authorized.getCapturedAt()).isNotNull();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository).save(order);
    }

    @Test
    void aFailedManualCaptureLeavesTheOrderUnpaid() {
        OrderRecord order = OrderRecord.builder().id(orderId).orderStatus(OrderStatus.ATTEMPTED).build();
        Payment authorized = authorizedPayment(order);
        when(router.capture(authorized)).thenReturn(new PaymentResult.Failure("DECLINED", "no"));

        service.capture(merchantId, paymentId);

        verify(transitions).apply(authorized, PaymentEvent.CAPTURE_FAIL);
        assertThat(authorized.getErrorCode()).isEqualTo("DECLINED");
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ATTEMPTED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void aCaptureThatSucceedsOnRetryClearsTheErrorOfTheAttemptThatFailed() {
        OrderRecord order = OrderRecord.builder().id(orderId).orderStatus(OrderStatus.ATTEMPTED).build();
        Payment authorized = authorizedPayment(order);
        authorized.setErrorCode("CAPTURE_DECLINED");
        authorized.setErrorDescription("The bank declined the capture");
        when(router.capture(authorized)).thenReturn(new PaymentResult.Success("REF"));

        service.capture(merchantId, paymentId);

        assertThat(authorized.getErrorCode()).isNull();
        assertThat(authorized.getErrorDescription()).isNull();
    }

    // ---- what happens when the processor call fails

    private static FeignException.NotFound notFound() {
        Request request = Request.create(Request.HttpMethod.POST, "http://vault/internal/vault/charge",
                Map.of(), new byte[0], StandardCharsets.UTF_8, null);
        return new FeignException.NotFound("404 CardToken not found: tok_secretvalue", request, null, null);
    }

    @Test
    void aReadTimeoutLeavesThePaymentAuthorizingBecauseTheChargeMayHaveGoneThrough() {
        when(router.initiate(any())).thenThrow(new RuntimeException("call failed", new SocketTimeoutException("Read timed out")));

        service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), null);

        ArgumentCaptor<PaymentResult> result = ArgumentCaptor.forClass(PaymentResult.class);
        verify(recorder).applyGatewayResult(eq(paymentId), result.capture());
        assertThat(result.getValue()).isInstanceOf(PaymentResult.Pending.class);
        verify(recorder, never()).compensateAuthorizationFailure(any(), anyString(), anyString());
    }

    @Test
    void aConnectTimeoutOrRefusedConnectionIsSafelyFailedBecauseNothingWasSent() {
        when(router.initiate(any())).thenThrow(new RuntimeException("x", new SocketTimeoutException("Connect timed out")));
        service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), null);

        // doThrow, because the mock already throws and `when(router.initiate(..))` would call it again
        org.mockito.Mockito.doThrow(new RuntimeException("x", new ConnectException("Connection refused")))
                .when(router).initiate(any());
        service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), null);

        verify(recorder, org.mockito.Mockito.times(2)).compensateAuthorizationFailure(
                eq(paymentId), eq("PAYMENT_GATEWAY_ROUTER_UNREACHABLE"), anyString());
        verify(recorder, never()).applyGatewayResult(any(), any());
    }

    @Test
    void theMerchantNeverSeesTheExceptionText() {
        when(router.initiate(any())).thenThrow(new IllegalStateException(
                "no suitable HttpMessageConverter found for request type [com.project.payflo.common_lib.dto.VaultChargeRequest]"));

        service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), null);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(recorder).compensateAuthorizationFailure(eq(paymentId), anyString(), description.capture());
        assertThat(description.getValue()).doesNotContain("HttpMessageConverter", "VaultChargeRequest", "common_lib");
    }

    @Test
    void anUnknownOrForeignCardTokenIsReportedAsAnInvalidToken() {
        when(router.initiate(any())).thenThrow(notFound());

        service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), null);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(recorder).compensateAuthorizationFailure(eq(paymentId), eq("CARD_TOKEN_INVALID"), description.capture());
        assertThat(description.getValue()).doesNotContain("tok_secretvalue");
    }

    private PaymentResponse earlierAttempt(UUID forOrder, PaymentMethod method) {
        return new PaymentResponse(paymentId, forOrder, merchantId, Money.inr(1000), PaymentStatus.AUTHORIZING, method,
                null, null, null, null, null);
    }

    @Test
    void aRetryOfTheSamePaymentWithTheSameKeyReturnsTheEarlierAttempt() {
        PaymentResponse earlier = earlierAttempt(orderId, PaymentMethod.CARD);
        when(recorder.findExistingAttempt(merchantId, "key-1")).thenReturn(Optional.of(earlier));

        PaymentResponse response = service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), "key-1");

        assertThat(response).isSameAs(earlier);
        verify(recorder, never()).recordPayment(any(), any(), any());
        verify(router, never()).initiate(any());
    }

    @Test
    void reusingAKeyForAnotherOrderIsRefusedNotReplayed() {
        when(recorder.findExistingAttempt(merchantId, "key-1")).thenReturn(Optional.of(earlierAttempt(UUID.randomUUID(), PaymentMethod.CARD)));

        assertThatThrownBy(() -> service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), "key-1"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verify(recorder, never()).recordPayment(any(), any(), any());
    }

    @Test
    void reusingAKeyForAnotherMethodIsRefusedNotReplayed() {
        when(recorder.findExistingAttempt(merchantId, "key-1")).thenReturn(Optional.of(earlierAttempt(orderId, PaymentMethod.UPI)));

        assertThatThrownBy(() -> service.initiate(merchantId, request(orderId, PaymentMethod.CARD, Map.of("token", "tok_x")), "key-1"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verify(recorder, never()).recordPayment(any(), any(), any());
    }
}
