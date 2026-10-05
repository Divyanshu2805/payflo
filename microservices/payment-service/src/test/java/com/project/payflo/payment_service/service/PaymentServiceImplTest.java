package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.payment_service.dto.request.PaymentInitRequest;
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
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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

    private final PaymentServiceImpl service = new PaymentServiceImpl(mock(OrderRepository.class),
            mock(PaymentRepository.class), router, mock(PaymentMapper.class), mock(PaymentTransitionService.class),
            mock(OutboxEventPublisher.class), recorder);

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
    void walletIsReportedAsUnsupportedNotAsAFailedPayment() {
        assertRejected(request(orderId, PaymentMethod.WALLET, Map.of("anything", "x")), "PAYMENT_METHOD_NOT_SUPPORTED");
    }

    @Test
    void aTokenThatIsTooLongIsRejected() {
        Map<String, Object> details = new HashMap<>();
        details.put("token", "t".repeat(201));
        assertRejected(request(orderId, PaymentMethod.CARD, details), "INVALID_PAYMENT_DETAILS");
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
}
