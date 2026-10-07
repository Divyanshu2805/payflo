package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Finishes settlements that were interrupted between steps (see {@link SettlementTransactionExecutor}):
 * <ul>
 *   <li><b>INITIATED for a while</b> — the payout was recorded but the transfer never reached the bank. The
 *       transfer is started again; the bank identifies it by the settlement id, so it can't be paid twice.</li>
 *   <li><b>PROCESSED but not marked</b> — the bank paid but payment-service wasn't told. The payments are
 *       marked settled now (and until then are held back from new payouts).</li>
 *   <li><b>TRANSFER_PENDING for too long</b> — the bank accepted the transfer and never answered. It is failed, so its
 *       payments are paid out again by the next run instead of waiting forever.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementRecoveryJob {

    // Long enough that a settlement still being worked on by the nightly run isn't picked up under it.
    private static final long GRACE_MINUTES = 2;

    private final SettlementRepository settlementRepository;
    private final SettlementTransactionExecutor executor;
    private final SettlementIntegrationGateway gateway;
    private final SettlementProperties properties;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "operations-service-settlement-recovery", lockAtMostFor = "5m", lockAtLeastFor = "10s")
    public void recover() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(GRACE_MINUTES);

        for (Settlement settlement : settlementRepository
                .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(SettlementStatus.INITIATED, cutoff)) {
            try {
                SettlementBankDetails details = gateway.getSettlementBankDetails(settlement.getMerchantId());
                log.warn("Restarting the transfer of settlement {}, which never reached the bank", settlement.getId());
                executor.startTransfer(settlement.getId(), settlement.getMerchantId(), settlement.getNetAmount(),
                        details, LocalDate.now());
            } catch (Exception e) {
                log.error("Could not restart settlement {}", settlement.getId(), e);
            }
        }

        LocalDateTime transferCutoff = LocalDateTime.now().minusMinutes(properties.getTransferTimeoutMinutes());
        for (Settlement settlement : settlementRepository
                .findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(SettlementStatus.TRANSFER_PENDING, transferCutoff)) {
            try {
                log.warn("Failing settlement {}: the bank never answered its transfer", settlement.getId());
                executor.resolveTransfer(settlement.getId(), "TRANSFER_TIMEOUT", "The bank did not confirm the transfer in time");
            } catch (Exception e) {
                log.error("Could not fail the unanswered settlement {}", settlement.getId(), e);
            }
        }

        for (Settlement settlement : settlementRepository
                .findTop100ByStatusAndPaymentsSettledAtIsNullAndProcessedAtBeforeOrderByProcessedAtAsc(
                        SettlementStatus.PROCESSED, cutoff)) {
            try {
                log.warn("Finishing settlement {}: paid out, but its payments aren't marked settled yet", settlement.getId());
                executor.finishPaymentMarking(settlement.getId());
            } catch (Exception e) {
                log.error("Could not mark the payments of settlement {} settled", settlement.getId(), e);
            }
        }
    }
}
