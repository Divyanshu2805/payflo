package com.project.payflo.payment_service.service.impl;

import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.enums.RefundStatus;
import com.project.payflo.payment_service.api.PaymentLookupService;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.repository.RefundRepository;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentLookupServiceImpl implements PaymentLookupService {

    // A payment is paid out while captured or partly refunded (net of the refunds the bank has completed).
    private static final List<PaymentStatus> SETTLEABLE = List.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);
    private static final List<RefundStatus> WAITING_ON_BANK = List.of(RefundStatus.PENDING, RefundStatus.PROCESSING);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentTransitionService paymentTransitionService;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentSettlementView> findUnsettledCapturedPayments(UUID merchantId, LocalDateTime capturedBefore,
                                                                     int page, int size) {
        List<Payment> payments = paymentRepository.findSettleable(merchantId, SETTLEABLE, capturedBefore,
                WAITING_ON_BANK, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 5000))).getContent();
        if (payments.isEmpty()) {
            return List.of();
        }

        Map<UUID, Long> refunded = new HashMap<>();
        for (Object[] row : refundRepository.sumProcessedByPayment(payments.stream().map(Payment::getId).toList())) {
            refunded.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        return payments.stream()
                .map(p -> new PaymentSettlementView(
                        p.getId(),
                        p.getAmount().getAmountUnits(),
                        refunded.getOrDefault(p.getId(), 0L).intValue(),
                        p.getAmount().getCurrency()))
                .collect(Collectors.toList());
    }

    /**
     * Moves paid-out payments to SETTLED through the state machine, so each gets a transition-log row. Safe to
     * repeat: anything not captured or partly refunded (already settled, say) is skipped, which is what lets
     * settlement retry this call after a failure.
     */
    @Override
    @Transactional
    public void markSettled(List<UUID> paymentIds) {
        LocalDateTime now = LocalDateTime.now();
        for (Payment payment : paymentRepository.findAllByIdForUpdate(paymentIds)) {
            if (!SETTLEABLE.contains(payment.getStatus())) {
                log.info("Not marking payment {} settled, it is {}", payment.getId(), payment.getStatus());
                continue;
            }
            paymentTransitionService.apply(payment, PaymentEvent.SETTLE);
            payment.setSettledAt(now);
        }
    }
}
