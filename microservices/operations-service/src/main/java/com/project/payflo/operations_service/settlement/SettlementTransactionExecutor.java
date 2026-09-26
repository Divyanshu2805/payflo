package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.operations_service.client.MerchantServiceClient;
import com.project.payflo.operations_service.client.PaymentServiceClient;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.entity.SettlementPayment;
import com.project.payflo.operations_service.entity.SettlementPaymentId;
import com.project.payflo.operations_service.outbox.OutboxEventPublisher;
import com.project.payflo.operations_service.repository.SettlementPaymentRepository;
import com.project.payflo.operations_service.repository.SettlementRepository;
import com.project.payflo.operations_service.settlement.dto.BankTransferResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementTransactionExecutor {

    private static final double FEE_RATE = 0.02;
    private static final double GST_RATE = 0.18;

    private final SettlementRepository settlementRepository;
    private final SettlementPaymentRepository settlementPaymentRepository;
    private final BankTransferProcessor bankTransferProcessor;
    private final OutboxEventPublisher outboxEventPublisher;
    private final SettlementIntegrationGateway settlementIntegrationGateway;

    // Payments settle only once the payout is confirmed, so until then they still look "unsettled" to
    // payment-service. A payment in a payout in one of these states must not go into another.
    private static final List<SettlementStatus> IN_FLIGHT = List.of(
            SettlementStatus.INITIATED, SettlementStatus.TRANSFER_PENDING);

    // Money holds an int, so one settlement's gross must fit in one.
    private static final long MAX_GROSS_UNITS_PER_SETTLEMENT = Integer.MAX_VALUE;

    @Transactional
    public void processForMerchant(UUID merchantId, LocalDate settlementDate) {
        List<PaymentSettlementView> captured = settlementIntegrationGateway.findUnsettledCaptured(merchantId);
        if (captured.isEmpty()) return;

        Set<UUID> inFlight = settlementPaymentRepository.findPaymentIdsInSettlements(merchantId, IN_FLIGHT);
        List<PaymentSettlementView> unsettledPayments = captured.stream()
                .filter(p -> !inFlight.contains(p.paymentId()))
                .toList();
        if (unsettledPayments.isEmpty()) return;

        // Checked before anything is written: a merchant with nowhere to pay out to is skipped, and
        // its payments stay captured for a later run.
        SettlementBankDetails bankDetails;
        try {
            bankDetails = settlementIntegrationGateway.getSettlementBankDetails(merchantId);
        } catch (Exception e) {
            log.error("Could not load bank details for merchantId: {}, skipping settlement", merchantId, e);
            return;
        }
        if (bankDetails == null || isBlank(bankDetails.accountNumber()) || isBlank(bankDetails.ifsc())) {
            log.warn("Skipping settlement for merchantId: {}: no settlement bank account on file ({} payments waiting)",
                    merchantId, unsettledPayments.size());
            return;
        }

        log.info("Processing {} unsettled payments for merchantId: {} on {} date",
                unsettledPayments.size(), merchantId, settlementDate);

        // One settlement per currency, and per slice small enough for its gross to fit Money's int.
        Map<String, List<PaymentSettlementView>> byCurrency = new LinkedHashMap<>();
        for (PaymentSettlementView payment : unsettledPayments) {
            byCurrency.computeIfAbsent(payment.currency(), c -> new ArrayList<>()).add(payment);
        }
        for (List<PaymentSettlementView> sameCurrency : byCurrency.values()) {
            for (List<PaymentSettlementView> slice : slicesWithinLimit(sameCurrency)) {
                settle(merchantId, settlementDate, slice, bankDetails);
            }
        }
    }

    private static List<List<PaymentSettlementView>> slicesWithinLimit(List<PaymentSettlementView> payments) {
        List<List<PaymentSettlementView>> slices = new ArrayList<>();
        List<PaymentSettlementView> current = new ArrayList<>();
        long currentGross = 0;
        for (PaymentSettlementView payment : payments) {
            if (!current.isEmpty() && currentGross + payment.amountUnits() > MAX_GROSS_UNITS_PER_SETTLEMENT) {
                slices.add(current);
                current = new ArrayList<>();
                currentGross = 0;
            }
            current.add(payment);
            currentGross += payment.amountUnits();
        }
        if (!current.isEmpty()) slices.add(current);
        return slices;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void settle(UUID merchantId, LocalDate settlementDate, List<PaymentSettlementView> unsettledPayments,
                        SettlementBankDetails settlementBankDetails) {
        // Sliced so the sum fits an int; the sum is widened anyway so a bug can't wrap silently.
        int grossAmount = Math.toIntExact(unsettledPayments.stream()
                .mapToLong(PaymentSettlementView::amountUnits)
                .sum());

        Money gross = Money.of(grossAmount, unsettledPayments.getFirst().currency());

        int fee = Math.toIntExact(Math.round(gross.getAmountUnits() * FEE_RATE));
        int gst = Math.toIntExact(Math.round(fee * GST_RATE));
        Money feeAmount = Money.of(fee, gross.getCurrency());
        Money gstAmount = Money.of(gst, gross.getCurrency());
        Money netAmount = gross.subtract(feeAmount).subtract(gstAmount);

        Settlement settlement = Settlement.builder()
                .merchantId(merchantId)
                .grossAmount(gross)
                // Refunds aren't built yet, so nothing is netted off; the column is NOT NULL.
                .refundAmount(Money.of(0, gross.getCurrency()))
                .feeAmount(feeAmount)
                .gstAmount(gstAmount)
                .netAmount(netAmount)
                .status(SettlementStatus.INITIATED)
                .build();

        settlementRepository.save(settlement);

        try {
            List<SettlementPayment> links = new ArrayList<>();
            for (PaymentSettlementView p : unsettledPayments) {
                links.add(SettlementPayment.builder()
                        .id(new SettlementPaymentId(settlement.getId(), p.paymentId()))
                        .settlement(settlement)
                        .build());
            }
            settlementPaymentRepository.saveAll(links);

            BankTransferResult bankTransferResult = bankTransferProcessor.initiate(settlement.getId(), merchantId, netAmount,
                    settlementBankDetails.accountNumber(), settlementBankDetails.ifsc());

            settlement.setStatus(SettlementStatus.TRANSFER_PENDING);
            settlement.setBankReference(bankTransferResult.registrationRef());

            settlementRepository.save(settlement);
        } catch (Exception e) {
            log.error("Settlement failed for settlementId: {} on date: {}", settlement.getId(), settlementDate, e);
            settlement.setStatus(SettlementStatus.FAILED);
            settlementRepository.save(settlement);
        }
    }

    @Transactional
    public void resolveTransfer(UUID settlementId,
                                String errorCode, String errorDescription) {

        Settlement settlement = settlementRepository.findById(settlementId).orElseThrow(
                () -> new ResourceNotFoundException("Settlement", settlementId));

        if (settlement.getStatus() != SettlementStatus.TRANSFER_PENDING) {
            log.info("Settlement resolved, skipping for id: {}", settlement.getId());
            return;
        }

        if (errorCode == null) { // success
            settlement.setStatus(SettlementStatus.PROCESSED);
            settlement.setProcessedAt(LocalDateTime.now());
            settlementRepository.save(settlement);

            List<SettlementPayment> settlementPaymentList = settlementPaymentRepository.findBySettlement(settlement);
            List<UUID> paymentIds = settlementPaymentList.stream()
                    .map(SettlementPayment::getId)
                    .map(SettlementPaymentId::getPaymentId)
                    .toList();
            settlementIntegrationGateway.markSettled(paymentIds);

            log.info("Settlement processed successfully, settlementId: {}", settlement.getId());
            outboxEventPublisher.publish(EventAggregateType.SETTLEMENT, settlementId,
                    "SETTLEMENT_PROCESSED", Map.of(
                            "settlementId", settlement.getId(),
                            "merchantId", settlement.getMerchantId(),
                            "status", settlement.getStatus().name(),
                            "settlementAmount", settlement.getNetAmount().getAmountUnits(),
                            "settlementCurrency", settlement.getNetAmount().getCurrency()
                    ));
        } else { // failed
            settlement.setStatus(SettlementStatus.FAILED);
            settlement.setFailureReason(errorCode + " : " + errorDescription);
            settlementRepository.save(settlement);
            log.warn("Settlement failed, settlementId: {}", settlement.getId());
            outboxEventPublisher.publish(EventAggregateType.SETTLEMENT, settlementId,
                    "SETTLEMENT_FAILED", Map.of(
                            "settlementId", settlement.getId(),
                            "merchantId", settlement.getMerchantId(),
                            "status", settlement.getStatus().name(),
                            "settlementAmount", settlement.getNetAmount().getAmountUnits(),
                            "settlementCurrency", settlement.getNetAmount().getCurrency()
                    ));
        }

    }


}




















