package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.exception.IdempotencyKeyReusedException;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.enums.RefundStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.payment_service.dto.request.CreateRefundRequest;
import com.project.payflo.payment_service.dto.response.RefundResponse;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.entity.Refund;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.repository.RefundRepository;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Refunds a merchant asks for. Creating one only reserves the money and records the request — the bank's
 * answer arrives later, from {@link RefundProcessingService}, the way a payment's does — so no remote call
 * ever runs inside the transaction.
 *
 * <p>A payment can be refunded while it is {@code CAPTURED} or {@code PARTIALLY_REFUNDED}, up to its amount in
 * total (refunds still waiting count, so two requests can't both take the last of it). Once it has been
 * paid out ({@code SETTLED}) it can't: that money has left, and taking it back needs an adjustment to a
 * later payout, which doesn't exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    // Refunds that already claim part of the payment's amount.
    public static final List<RefundStatus> ACTIVE = List.of(RefundStatus.PENDING, RefundStatus.PROCESSING, RefundStatus.PROCESSED);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentTransitionService paymentTransitionService;
    private final OutboxEventPublisher eventPublisher;

    @Transactional
    public RefundResponse create(UUID merchantId, UUID paymentId, CreateRefundRequest request, String idempotencyKey) {
        RefundResponse replay = replay(merchantId, paymentId, request, idempotencyKey);
        if (replay != null) {
            return replay;
        }

        // Locked, so refunds of one payment queue up and each sees the ones before it.
        Payment payment = paymentRepository.findByIdAndMerchantIdForUpdate(paymentId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        // Checked again under the lock: a retry that arrived while the first request was still running.
        replay = replay(merchantId, paymentId, request, idempotencyKey);
        if (replay != null) {
            return replay;
        }

        if (payment.getStatus() == PaymentStatus.SETTLED) {
            throw new BusinessRuleViolationException("PAYMENT_ALREADY_SETTLED",
                    "This payment has already been paid out to the merchant and can no longer be refunded");
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessRuleViolationException("PAYMENT_NOT_REFUNDABLE",
                    "Only a captured payment can be refunded, this one is " + payment.getStatus());
        }

        long remaining = payment.getAmount().getAmountUnits() - refundRepository.sumAmountUnits(paymentId, ACTIVE);
        Integer requested = request != null ? request.amountUnits() : null;
        if (remaining <= 0 || (requested != null && requested > remaining)) {
            throw new BusinessRuleViolationException("REFUND_EXCEEDS_PAYMENT",
                    "Only " + Math.max(remaining, 0) + " of this payment can still be refunded");
        }
        int amount = requested != null ? requested : (int) remaining;

        Refund refund = refundRepository.save(Refund.builder()
                .payment(payment)
                .merchantId(merchantId)
                .amount(Money.of(amount, payment.getAmount().getCurrency()))
                .status(RefundStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .notes(request != null ? request.notes() : null)
                .build());

        PaymentStatus before = payment.getStatus();
        paymentTransitionService.apply(payment, PaymentEvent.REFUND_INIT);
        paymentRepository.save(payment);

        publishRefundEvent(refund, "REFUND_CREATED");
        if (before != payment.getStatus()) {
            publishPaymentStatusChanged(payment);
        }
        log.info("Refund {} of {} created for payment {}", refund.getId(), amount, paymentId);
        return RefundResponse.from(refund);
    }

    @Transactional(readOnly = true)
    public RefundResponse get(UUID merchantId, UUID refundId) {
        return refundRepository.findByIdAndMerchantId(refundId, merchantId)
                .map(RefundResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Refund", refundId));
    }

    @Transactional(readOnly = true)
    public PageResponse<RefundResponse> list(UUID merchantId, RefundStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(PageResponse.clampPage(page), PageResponse.clampSize(size));
        Slice<Refund> refunds = status == null
                ? refundRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                : refundRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, status, pageable);
        return PageResponse.of(refunds, RefundResponse::from);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> listForPayment(UUID merchantId, UUID paymentId) {
        paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        return refundRepository.findByPayment_IdAndMerchantIdOrderByCreatedAtAsc(paymentId, merchantId).stream()
                .map(RefundResponse::from)
                .toList();
    }

    private RefundResponse replay(UUID merchantId, UUID paymentId, CreateRefundRequest request, String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        RefundResponse found = refundRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .map(RefundResponse::from)
                .orElse(null);
        // The key is only a replay of the same request: another payment, or another amount, is a different one.
        if (found != null && (!found.paymentId().equals(paymentId) || (request != null && request.amountUnits() != null
                && (long) request.amountUnits() != (long) found.amount().getAmountUnits()))) {
            throw new IdempotencyKeyReusedException(
                    "This idempotency key was already used for a different refund. Use a new key for a new request.");
        }
        return found;
    }

    public void publishRefundEvent(Refund refund, String eventType) {
        eventPublisher.publish(EventAggregateType.REFUND, refund.getId(), eventType,
                Map.of("refundId", refund.getId().toString(),
                        "paymentId", refund.getPayment().getId().toString(),
                        "merchantId", refund.getMerchantId().toString(),
                        "refundStatus", refund.getStatus().name(),
                        "amountUnits", refund.getAmount().getAmountUnits(),
                        "amountCurrency", refund.getAmount().getCurrency()));
    }

    public void publishPaymentStatusChanged(Payment payment) {
        eventPublisher.publish(EventAggregateType.PAYMENT, payment.getId(), "PAYMENT_STATUS_CHANGED",
                Map.of("orderId", payment.getOrder().getId().toString(),
                        "paymentId", payment.getId().toString(),
                        "merchantId", payment.getMerchantId().toString(),
                        "paymentStatus", payment.getStatus().name(),
                        "amountUnits", payment.getAmount().getAmountUnits(),
                        "amountCurrency", payment.getAmount().getCurrency(),
                        "paymentMethod", payment.getMethod()));
    }
}
