package com.project.payflo.payment_service.service.impl;

import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.payment_service.dto.request.PaymentInitRequest;
import com.project.payflo.payment_service.dto.response.PaymentResponse;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.gateway.PaymentGatewayRouter;
import com.project.payflo.payment_service.gateway.dto.PaymentRequest;
import com.project.payflo.payment_service.gateway.dto.PaymentResult;
import com.project.payflo.payment_service.mapper.PaymentMapper;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.OrderRepository;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.saga.PaymentAuthorizationRecorder;
import com.project.payflo.payment_service.service.PaymentService;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRouter paymentGatewayRouter;
    private final PaymentMapper paymentMapper;
    private final PaymentTransitionService paymentTransitionService;
    private final OutboxEventPublisher eventPublisher;
    private final PaymentAuthorizationRecorder paymentAuthorizationRecorder;

    @Override
    public PaymentResponse initiate(UUID merchantId, PaymentInitRequest request, String idempotencyKey) {

        // Before anything is written: a request that can never work is a 400, not a failed payment.
        validateMethodDetails(request);

        if (idempotencyKey != null) {
            var existing = paymentAuthorizationRecorder.findExistingAttempt(merchantId, idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency replay for paymentId: {}", existing.get().id());
                return existing.get();
            }
        }

        Payment payment = paymentAuthorizationRecorder.recordPayment(merchantId, request, idempotencyKey);

        PaymentRequest paymentRequest = new PaymentRequest(payment.getId(),
                request.orderId(), merchantId,
                payment.getAmount(), request.method(),
                request.methodDetails());

        PaymentResult result;
        try {
            result = paymentGatewayRouter.initiate(paymentRequest);
        } catch (Exception e) {
            return handleGatewayException(payment.getId(), e);
        }

        return paymentAuthorizationRecorder.applyGatewayResult(payment.getId(), result);
    }

    // The exception's text never reaches the merchant (it names classes and internal types), only what
    // actually happened to their payment.
    private PaymentResponse handleGatewayException(UUID paymentId, Exception e) {
        log.error("Payment gateway call failed, paymentId: {}", paymentId, e);

        if (isAmbiguousTimeout(e)) {
            // The request may have reached the acquirer and the charge may have gone through, so don't claim
            // it failed: leave the payment AUTHORIZING. The bank's answer resolves it, or, failing that,
            // PaymentTimeoutSweeper does once it has waited long enough.
            log.warn("Gateway call timed out, leaving payment AUTHORIZING, paymentId: {}", paymentId);
            return paymentAuthorizationRecorder.applyGatewayResult(paymentId, new PaymentResult.Pending(null));
        }
        if (hasCause(e, FeignException.NotFound.class)) {
            return paymentAuthorizationRecorder.compensateAuthorizationFailure(paymentId, "CARD_TOKEN_INVALID",
                    "The card token is invalid, has been revoked, or belongs to another merchant");
        }
        return paymentAuthorizationRecorder.compensateAuthorizationFailure(paymentId,
                "PAYMENT_GATEWAY_ROUTER_UNREACHABLE", "The payment processor could not be reached; no charge was made");
    }

    // A read timeout after the request was sent: the outcome is unknown. A connect timeout, a refused
    // connection or an open circuit breaker means nothing was sent.
    private static boolean isAmbiguousTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException timeout) {
                String message = timeout.getMessage();
                return message == null || !message.toLowerCase().contains("connect");
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static void validateMethodDetails(PaymentInitRequest request) {
        Map<String, Object> details = request.methodDetails();
        switch (request.method()) {
            case CARD -> requireText(details, "token");
            case UPI -> requireText(details, "vpa");
            case NETBANKING -> requireText(details, "bank");
            case WALLET -> throw new BusinessRuleViolationException("PAYMENT_METHOD_NOT_SUPPORTED",
                    "Payment method WALLET is not supported");
        }
    }

    private static void requireText(Map<String, Object> details, String field) {
        Object value = details != null ? details.get(field) : null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > 200) {
            throw new BusinessRuleViolationException("INVALID_PAYMENT_DETAILS",
                    "methodDetails." + field + " is required");
        }
    }


    @Override
    @Transactional
    public PaymentResponse capture(UUID merchantId, UUID paymentId) {

//        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
//                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        Payment payment = paymentRepository.findByIdAndMerchantIdForUpdate(paymentId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_REQUEST);

        PaymentResult paymentResult = paymentGatewayRouter.capture(payment.getMethod(), paymentId);

        if(paymentResult instanceof  PaymentResult.Success success) {
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_SUCCESS);
            payment.setCapturedAt(LocalDateTime.now());
            log.info("Payment captured, paymentID: {}", paymentId);
        } else if(paymentResult instanceof  PaymentResult.Failure failure) {
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_FAIL);
            payment.setErrorCode(failure.errorCode());
            payment.setErrorDescription(failure.errorDescription());
            log.warn("Payment capture failed, paymentID: {}", paymentId);
        }

        payment = paymentRepository.save(payment);

        eventPublisher.publish(EventAggregateType.PAYMENT, payment.getId(), "PAYMENT_STATUS_CHANGED",
                Map.of("orderId", payment.getOrder().getId().toString(),
                        "paymentId", payment.getId().toString(),
                        "merchantId", merchantId.toString(),
                        "paymentStatus", payment.getStatus().name(),
                        "amountUnits", payment.getAmount().getAmountUnits(),
                        "amountCurrency", payment.getAmount().getCurrency(),
                        "paymentMethod", payment.getMethod()
                )
        );

        return paymentMapper.toResponse(payment);
    }

    @Override
    @Transactional
    public void resolveAuthorization(UUID paymentId, boolean approve,
                                     String bankRef, String errorCode, String errorDescription) {

//        Payment payment = paymentRepository.findById(paymentId)
//                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        if (payment.getStatus() != PaymentStatus.AUTHORIZING) {
            log.warn("Payment is not in Authorizing state, paymentID: {}, status: {}", paymentId, payment.getStatus());
            return;
        }

        OrderRecord orderRecord = payment.getOrder();

        if (approve) {
            paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_SUCCESS);
            payment.setBankReference(bankRef);
            payment.setAuthorizedAt(LocalDateTime.now());

            // Auto-capture
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_REQUEST);
            PaymentResult captureResult = paymentGatewayRouter.capture(payment.getMethod(), paymentId);

            if(captureResult instanceof PaymentResult.Success success) {
                paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_SUCCESS);
                payment.setCapturedAt(LocalDateTime.now());
                orderRecord.setOrderStatus(OrderStatus.PAID);
            } else if (captureResult instanceof  PaymentResult.Failure failure){
                paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_FAIL);
                payment.setErrorCode(failure.errorCode());
                payment.setErrorDescription(failure.errorDescription());
            }
        } else {
            paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_FAIL);
            payment.setErrorCode(errorCode);
            payment.setErrorDescription(errorDescription);
        }

        paymentRepository.save(payment);
        orderRepository.save(orderRecord);

        eventPublisher.publish(EventAggregateType.PAYMENT, payment.getId(), "PAYMENT_STATUS_CHANGED",
                Map.of("orderId", payment.getOrder().getId().toString(),
                        "paymentId", payment.getId().toString(),
                        "merchantId", payment.getMerchantId().toString(),
                        "paymentStatus", payment.getStatus().name(),
                        "amountUnits", payment.getAmount().getAmountUnits(),
                        "amountCurrency", payment.getAmount().getCurrency(),
                        "paymentMethod", payment.getMethod()
                )
        );
    }
}











