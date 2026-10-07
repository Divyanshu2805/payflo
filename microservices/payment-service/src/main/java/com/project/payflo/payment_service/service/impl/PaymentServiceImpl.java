package com.project.payflo.payment_service.service.impl;

import com.project.payflo.common_lib.exception.IdempotencyKeyReusedException;
import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentMethod;
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
import com.project.payflo.payment_service.service.AuthorizationResolution;
import com.project.payflo.payment_service.service.PaymentService;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import com.project.payflo.payment_service.velocity.CardVelocityGuard;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
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
    private final CardVelocityGuard cardVelocityGuard;

    @Override
    public PaymentResponse initiate(UUID merchantId, PaymentInitRequest request, String idempotencyKey) {

        // Before anything is written: a request that can never work is a 400, not a failed payment.
        validateMethodDetails(request);

        if (idempotencyKey != null) {
            var existing = paymentAuthorizationRecorder.findExistingAttempt(merchantId, idempotencyKey);
            if (existing.isPresent()) {
                // The key is only a replay of the same request: an order or method that differs is a different one.
                if (!existing.get().orderId().equals(request.orderId()) || existing.get().method() != request.method()) {
                    throw new IdempotencyKeyReusedException(
                            "This idempotency key was already used for a different payment. Use a new key for a new request.");
                }
                log.info("Idempotency replay for paymentId: {}", existing.get().id());
                return existing.get();
            }
        }

        // A merchant whose card payments mostly fail is refused for a while (card testing); checked before anything
        // is written, so a refused request leaves no payment behind.
        boolean isCard = request.method() == PaymentMethod.CARD;
        if (isCard) {
            cardVelocityGuard.requireAllowed(merchantId);
        }

        Payment payment = paymentAuthorizationRecorder.recordPayment(merchantId, request, idempotencyKey);
        if (isCard) {
            cardVelocityGuard.recordAttempt(merchantId);
        }

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
            case WALLET -> requireText(details, "wallet");
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

        PaymentResult paymentResult = paymentGatewayRouter.capture(payment);

        if(paymentResult instanceof  PaymentResult.Success success) {
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_SUCCESS);
            payment.setCapturedAt(LocalDateTime.now());
            // A capture that succeeds on retry no longer carries the error of the attempt that failed.
            payment.setErrorCode(null);
            payment.setErrorDescription(null);
            // A captured payment pays its order, as in resolveAuthorization; this path used to leave it ATTEMPTED.
            OrderRecord order = payment.getOrder();
            order.setOrderStatus(OrderStatus.PAID);
            orderRepository.save(order);
            log.info("Payment captured, paymentID: {}", paymentId);
        } else if(paymentResult instanceof  PaymentResult.Failure failure) {
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_FAIL);
            payment.setErrorCode(failure.errorCode());
            payment.setErrorDescription(failure.errorDescription());
            log.warn("Payment capture failed, paymentID: {}", paymentId);
        }

        payment = paymentRepository.save(payment);

        publishStatusChanged(payment);

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

        if (applyAuthorizationAnswer(payment, approve, bankRef, errorCode, errorDescription)) {
            paymentRepository.save(payment);
            orderRepository.save(payment.getOrder());
            publishStatusChanged(payment);
        }
    }

    // The same rules as resolveAuthorization, for a whole batch in one transaction. The payments are locked in one
    // query (in id order, so two batches can't deadlock) and their orders loaded in one more, so what follows is
    // changes to managed entities that are written in JDBC batches at commit: a handful of statements and one log
    // flush for the batch where one payment at a time costs a round trip for each, and a flush of its own.
    @Override
    @Transactional
    public void resolveAuthorizations(List<AuthorizationResolution> resolutions) {
        if (resolutions.isEmpty()) {
            return;
        }
        List<UUID> ids = resolutions.stream().map(AuthorizationResolution::paymentId).distinct().sorted().toList();
        Map<UUID, Payment> payments = new HashMap<>();
        for (Payment payment : paymentRepository.findAllByIdForUpdate(ids)) {
            payments.put(payment.getId(), payment);
        }
        // Reading an order's id off the payment's lazy reference doesn't load it; this query does, for every order at once.
        orderRepository.findAllById(payments.values().stream().map(p -> p.getOrder().getId()).distinct().toList());

        for (AuthorizationResolution resolution : resolutions) {
            Payment payment = payments.get(resolution.paymentId());
            if (payment == null) {
                log.warn("Payment not found while resolving a batch, paymentID: {}", resolution.paymentId());
                continue;
            }
            if (applyAuthorizationAnswer(payment, resolution.approve(), resolution.bankRef(),
                    resolution.errorCode(), resolution.errorDescription())) {
                publishStatusChanged(payment);
            }
        }
    }

    /** Applies the bank's answer to a locked payment; false when the payment is no longer waiting for one. */
    private boolean applyAuthorizationAnswer(Payment payment, boolean approve,
                                             String bankRef, String errorCode, String errorDescription) {
        if (payment.getStatus() != PaymentStatus.AUTHORIZING) {
            log.warn("Payment is not in Authorizing state, paymentID: {}, status: {}", payment.getId(), payment.getStatus());
            return false;
        }

        OrderRecord orderRecord = payment.getOrder();

        if (approve) {
            paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_SUCCESS);
            payment.setBankReference(bankRef);
            payment.setAuthorizedAt(LocalDateTime.now());

            // Auto-capture
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_REQUEST);
            PaymentResult captureResult = paymentGatewayRouter.capture(payment);

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
            // The bank said no to this card: one more decline towards the merchant's card-testing count. Counted
            // once the decision is committed, so a Redis call never holds the payment (or a batch) locked.
            if (payment.getMethod() == PaymentMethod.CARD) {
                UUID merchantId = payment.getMerchantId();
                afterCommit(() -> cardVelocityGuard.recordDecline(merchantId, errorCode));
            }
        }
        return true;
    }

    // Runs now when there is no transaction to wait for (a unit test, say).
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // errorCode rides along when there is one, so a webhook receiver can tell an authorization held after a
    // refused capture from one that is simply waiting to be captured.
    private void publishStatusChanged(Payment payment) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", payment.getOrder().getId().toString());
        payload.put("paymentId", payment.getId().toString());
        payload.put("merchantId", payment.getMerchantId().toString());
        payload.put("paymentStatus", payment.getStatus().name());
        payload.put("amountUnits", payment.getAmount().getAmountUnits());
        payload.put("amountCurrency", payment.getAmount().getCurrency());
        payload.put("paymentMethod", payment.getMethod());
        if (payment.getErrorCode() != null) {
            payload.put("errorCode", payment.getErrorCode());
        }
        eventPublisher.publish(EventAggregateType.PAYMENT, payment.getId(), "PAYMENT_STATUS_CHANGED", payload);
    }
}











