package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.repository.SettlementPaymentRepository;
import com.project.payflo.operations_service.settlement.dto.BankTransferResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pays one merchant out. This used to be a single transaction around calls to payment-service,
 * merchant-service and the bank; it is now a sequence of short transactions ({@link SettlementRecorder}) with
 * the remote calls between them, so no database connection is held while a remote service is slow, and a
 * crash between two steps leaves a state {@link SettlementRecoveryJob} knows how to finish:
 *
 * <pre>
 *   INITIATED --transfer accepted--> TRANSFER_PENDING --bank says yes--> PROCESSED --payments marked--> (done)
 *        |                                   |
 *        +--transfer refused--> FAILED       +--bank says no--> FAILED
 * </pre>
 *
 * A payment is excluded from new payouts for as long as it is in one that is under way, and becomes payable
 * again if that payout FAILED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementTransactionExecutor {

    // Payments in a payout with one of these statuses are spoken for.
    private static final List<SettlementStatus> IN_FLIGHT = List.of(
            SettlementStatus.INITIATED, SettlementStatus.TRANSFER_PENDING);

    // Money holds an int, so one settlement's gross must fit in one.
    private static final long MAX_GROSS_UNITS_PER_SETTLEMENT = Integer.MAX_VALUE;

    // payment-service marks payments settled this many at a time.
    private static final int MARK_BATCH = 500;

    private final SettlementPaymentRepository settlementPaymentRepository;
    private final BankTransferProcessor bankTransferProcessor;
    private final SettlementIntegrationGateway settlementIntegrationGateway;
    private final SettlementRecorder settlementRecorder;
    private final SettlementProperties properties;

    public void processForMerchant(UUID merchantId, LocalDate settlementDate) {
        List<PaymentSettlementView> captured = fetchSettleable(merchantId);
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

    // Pages through payment-service's answer, oldest first, up to the per-run cap, and only payments old
    // enough to have cleared the hold.
    private List<PaymentSettlementView> fetchSettleable(UUID merchantId) {
        LocalDateTime capturedBefore = LocalDateTime.now().minusDays(properties.getHoldDays());
        List<PaymentSettlementView> all = new ArrayList<>();
        for (int page = 0; all.size() < properties.getMaxPaymentsPerRun(); page++) {
            List<PaymentSettlementView> batch = settlementIntegrationGateway.findUnsettledCaptured(
                    merchantId, capturedBefore, page, properties.getPageSize());
            all.addAll(batch);
            if (batch.size() < properties.getPageSize()) break;
        }
        return all;
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

    private void settle(UUID merchantId, LocalDate settlementDate, List<PaymentSettlementView> payments,
                        SettlementBankDetails bankDetails) {
        String currency = payments.getFirst().currency();
        // Widened so a bug can't wrap silently; the slicing keeps the real values inside an int.
        int gross = Math.toIntExact(payments.stream().mapToLong(PaymentSettlementView::amountUnits).sum());
        int refunds = Math.toIntExact(payments.stream().mapToLong(PaymentSettlementView::refundedAmountUnits).sum());

        // The fee is charged on what the merchant kept: refunded money wasn't earned.
        int kept = gross - refunds;
        int fee = percentOf(kept, properties.getFeeRate());
        int gst = percentOf(fee, properties.getGstRate());
        Money net = Money.of(kept - fee - gst, currency);

        // Step 1. From here the payments are spoken for.
        Settlement settlement = settlementRecorder.createInitiated(merchantId, Money.of(gross, currency),
                Money.of(refunds, currency), Money.of(fee, currency), Money.of(gst, currency), net,
                payments.stream().map(PaymentSettlementView::paymentId).toList());

        startTransfer(settlement.getId(), merchantId, net, bankDetails, settlementDate);
    }

    /**
     * Step 2: hand the transfer to the bank. The bank is given the settlement id as its reference, which is what
     * makes repeating this call (after a crash, from the recovery job) safe: it identifies the same transfer.
     */
    void startTransfer(UUID settlementId, UUID merchantId, Money net, SettlementBankDetails bankDetails,
                       LocalDate settlementDate) {
        try {
            BankTransferResult result = bankTransferProcessor.initiate(settlementId, merchantId, net,
                    bankDetails.accountNumber(), bankDetails.ifsc());
            settlementRecorder.markTransferPending(settlementId, result.registrationRef());
        } catch (Exception e) {
            log.error("Settlement transfer failed for settlementId: {} on date: {}", settlementId, settlementDate, e);
            settlementRecorder.markFailed(settlementId, "TRANSFER_NOT_STARTED : " + e.getClass().getSimpleName());
        }
    }

    /** The bank's answer to a transfer: errorCode is null for success. */
    public void resolveTransfer(UUID settlementId, String errorCode, String errorDescription) {
        if (errorCode != null) {
            settlementRecorder.markBankFailed(settlementId, errorCode, errorDescription);
            log.warn("Settlement failed, settlementId: {}", settlementId);
            return;
        }

        Optional<Settlement> processed = settlementRecorder.markProcessed(settlementId);
        if (processed.isEmpty()) {
            log.info("Settlement resolved, skipping for id: {}", settlementId);
            return;
        }
        log.info("Settlement processed successfully, settlementId: {}", settlementId);

        finishPaymentMarking(settlementId);
    }

    /**
     * Step 4: tell payment-service the covered payments are paid out. Repeatable (payment-service skips ones
     * already SETTLED). If it fails the settlement stays PROCESSED-but-unmarked, its payments stay held back,
     * and the recovery job tries again.
     */
    void finishPaymentMarking(UUID settlementId) {
        List<UUID> paymentIds = settlementRecorder.paymentIds(settlementId);
        for (int from = 0; from < paymentIds.size(); from += MARK_BATCH) {
            settlementIntegrationGateway.markSettled(paymentIds.subList(from, Math.min(from + MARK_BATCH, paymentIds.size())));
        }
        settlementRecorder.markPaymentsSettled(settlementId);
    }

    private static int percentOf(int amount, double rate) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rate))
                .setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
