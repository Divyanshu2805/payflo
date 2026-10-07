package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.entity.SettlementPayment;
import com.project.payflo.operations_service.entity.SettlementPaymentId;
import com.project.payflo.operations_service.outbox.OutboxEventPublisher;
import com.project.payflo.operations_service.repository.SettlementPaymentRepository;
import com.project.payflo.operations_service.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The database steps of a settlement, each its own short transaction. They used to be one long transaction
 * wrapped around calls to payment-service, merchant-service and the bank, holding a connection (and, for a
 * crash, an unknown state) across all of them. Now the orchestrator ({@link SettlementTransactionExecutor})
 * makes the remote calls between these steps, and {@link SettlementRecoveryJob} finishes any it was
 * interrupted in.
 */
@Component
@RequiredArgsConstructor
public class SettlementRecorder {

    private final SettlementRepository settlementRepository;
    private final SettlementPaymentRepository settlementPaymentRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    /** Step 1: the payout and the payments it covers are recorded, in INITIATED. */
    @Transactional
    public Settlement createInitiated(UUID merchantId, Money gross, Money refunds, Money fee, Money gst, Money net,
                                      List<UUID> paymentIds) {
        Settlement settlement = settlementRepository.save(Settlement.builder()
                .merchantId(merchantId)
                .grossAmount(gross)
                .refundAmount(refunds)
                .feeAmount(fee)
                .gstAmount(gst)
                .netAmount(net)
                .status(SettlementStatus.INITIATED)
                .build());

        settlementPaymentRepository.saveAll(paymentIds.stream()
                .map(paymentId -> SettlementPayment.builder()
                        .id(new SettlementPaymentId(settlement.getId(), paymentId))
                        .settlement(settlement)
                        .build())
                .toList());
        return settlement;
    }

    /** Step 2: the bank has the transfer. */
    @Transactional
    public void markTransferPending(UUID settlementId, String bankReference) {
        Settlement settlement = lock(settlementId);
        if (settlement.getStatus() != SettlementStatus.INITIATED) {
            return;
        }
        settlement.setStatus(SettlementStatus.TRANSFER_PENDING);
        settlement.setBankReference(bankReference);
    }

    /** The transfer could not be started; the payments become payable again in the next run. */
    @Transactional
    public void markFailed(UUID settlementId, String reason) {
        Settlement settlement = lock(settlementId);
        if (settlement.getStatus() != SettlementStatus.INITIATED) {
            return;
        }
        settlement.setStatus(SettlementStatus.FAILED);
        settlement.setFailureReason(truncate(reason));
        publish(settlement, "SETTLEMENT_FAILED");
    }

    /** Step 3 (bank said yes): the payout is done. Empty if it wasn't waiting on the bank any more. */
    @Transactional
    public Optional<Settlement> markProcessed(UUID settlementId) {
        Settlement settlement = lock(settlementId);
        if (settlement.getStatus() != SettlementStatus.TRANSFER_PENDING) {
            return Optional.empty();
        }
        settlement.setStatus(SettlementStatus.PROCESSED);
        settlement.setProcessedAt(LocalDateTime.now());
        publish(settlement, "SETTLEMENT_PROCESSED");
        return Optional.of(settlement);
    }

    /** Step 3 (bank said no). */
    @Transactional
    public void markBankFailed(UUID settlementId, String errorCode, String errorDescription) {
        Settlement settlement = lock(settlementId);
        if (settlement.getStatus() != SettlementStatus.TRANSFER_PENDING) {
            return;
        }
        settlement.setStatus(SettlementStatus.FAILED);
        settlement.setFailureReason(truncate(errorCode + " : " + errorDescription));
        publish(settlement, "SETTLEMENT_FAILED");
    }

    /** Step 4: payment-service has marked the covered payments SETTLED. */
    @Transactional
    public void markPaymentsSettled(UUID settlementId) {
        Settlement settlement = lock(settlementId);
        if (settlement.getPaymentsSettledAt() == null) {
            settlement.setPaymentsSettledAt(LocalDateTime.now());
        }
    }

    @Transactional(readOnly = true)
    public List<UUID> paymentIds(UUID settlementId) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", settlementId));
        return settlementPaymentRepository.findBySettlement(settlement).stream()
                .map(sp -> sp.getId().getPaymentId())
                .toList();
    }

    private Settlement lock(UUID settlementId) {
        return settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", settlementId));
    }

    private void publish(Settlement settlement, String eventType) {
        outboxEventPublisher.publish(EventAggregateType.SETTLEMENT, settlement.getId(), eventType, Map.of(
                "settlementId", settlement.getId().toString(),
                "merchantId", settlement.getMerchantId().toString(),
                "status", settlement.getStatus().name(),
                "settlementAmount", settlement.getNetAmount().getAmountUnits(),
                "settlementCurrency", settlement.getNetAmount().getCurrency()));
    }

    private static String truncate(String value) {
        return value != null && value.length() > 255 ? value.substring(0, 255) : value;
    }
}
