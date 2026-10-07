package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.enums.RefundStatus;
import com.project.payflo.common_lib.util.RandomizerUtil;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.entity.Refund;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.repository.RefundRepository;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The (simulated) bank's answer to a refund, one refund per transaction. As with a payment's bank callback,
 * the outcome is derived from the refund's id, so a given refund always gets the same answer.
 */
@Slf4j
@Service
public class RefundProcessingService {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransitionService paymentTransitionService;
    private final RefundService refundService;
    private final int successRate;

    public RefundProcessingService(RefundRepository refundRepository,
                                   PaymentRepository paymentRepository,
                                   PaymentTransitionService paymentTransitionService,
                                   RefundService refundService,
                                   @Value("${payment.refund.success-rate:95}") int successRate) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paymentTransitionService = paymentTransitionService;
        this.refundService = refundService;
        this.successRate = successRate;
    }

    /** @return false if the refund was no longer waiting (or not old enough), true if it was resolved */
    @Transactional
    public boolean resolve(UUID refundId, LocalDateTime createdBefore) {
        Refund peek = refundRepository.findById(refundId).orElse(null);
        if (peek == null) {
            return false;
        }
        // The payment first, then the refund: the same order refund creation locks them in.
        Payment payment = paymentRepository.findByIdForUpdate(peek.getPayment().getId()).orElse(null);
        Refund refund = refundRepository.findByIdForUpdate(refundId).orElse(null);
        if (payment == null || refund == null || refund.getStatus() != RefundStatus.PENDING
                || !refund.getCreatedAt().isBefore(createdBefore)) {
            return false;
        }

        PaymentStatus before = payment.getStatus();
        if (approves(refund)) {
            refund.setStatus(RefundStatus.PROCESSED);
            refund.setProcessedAt(LocalDateTime.now());
            refund.setBankReference("SIM_REFUND_" + RandomizerUtil.randomBase64(8));
            refundRepository.saveAndFlush(refund);

            if (refundRepository.sumAmountUnits(payment.getId(), List.of(RefundStatus.PROCESSED))
                    >= payment.getAmount().getAmountUnits()) {
                paymentTransitionService.apply(payment, PaymentEvent.REFUND_COMPLETE);
                payment.setRefundedAt(LocalDateTime.now());
            }
            refundService.publishRefundEvent(refund, "REFUND_PROCESSED");
        } else {
            refund.setStatus(RefundStatus.FAILED);
            refund.setProcessedAt(LocalDateTime.now());
            refund.setErrorCode("SIM_REFUND_DECLINED");
            refund.setErrorDescription("Simulated bank declined the refund");
            refundRepository.saveAndFlush(refund);

            // The payment only goes back to CAPTURED if nothing else is refunded or on its way to being.
            if (payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                    && refundRepository.sumAmountUnits(payment.getId(), RefundService.ACTIVE) == 0) {
                paymentTransitionService.apply(payment, PaymentEvent.REFUND_FAIL);
            }
            refundService.publishRefundEvent(refund, "REFUND_FAILED");
        }

        paymentRepository.save(payment);
        if (before != payment.getStatus()) {
            refundService.publishPaymentStatusChanged(payment);
        }
        return true;
    }

    private boolean approves(Refund refund) {
        return Math.abs(refund.getId().hashCode()) % 100 < successRate;
    }
}
