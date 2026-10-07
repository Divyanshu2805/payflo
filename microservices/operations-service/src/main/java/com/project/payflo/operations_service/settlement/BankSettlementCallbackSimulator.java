package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BankSettlementCallbackSimulator {

    private final SettlementRepository settlementRepository;
    private final SettlementTransactionExecutor settlementTransactionExecutor;
    private final PayoutOutcomeDecider payoutOutcomeDecider;

    @Scheduled(fixedDelayString = "5000")
    @SchedulerLock(name = "operations-service-bank-settlement-simulator", lockAtMostFor = "10s", lockAtLeastFor = "1s")
    public void processCallbacks() {
        List<Settlement> settlements = settlementRepository.findByStatus(SettlementStatus.TRANSFER_PENDING);
        if (settlements.isEmpty()) return;

        for (Settlement settlement: settlements) {
            try {
                simulateCallback(settlement);
            } catch (Exception e) {
                // One settlement failing (payment-service briefly down, say) must not hold up the rest; the
                // recovery job finishes it.
                log.error("Settlement callback failed for settlementId: {}", settlement.getId(), e);
            }
        }
    }

    private void simulateCallback(Settlement settlement) {
        // updatedAt is when the bank accepted the transfer: the settlement went TRANSFER_PENDING and wasn't touched since
        PayoutOutcomeDecider.Outcome outcome =
                payoutOutcomeDecider.decide(settlement.getId(), settlement.getUpdatedAt(), LocalDateTime.now());

        switch (outcome.kind()) {
            case WAIT -> log.debug("Settlement {} is still with the bank", settlement.getId());
            case SUCCESS -> {
                log.info("Initiating settlement callback for settlementId: {}", settlement.getId());
                settlementTransactionExecutor.resolveTransfer(settlement.getId(), null, null);
            }
            case FAILURE -> {
                log.warn("The bank declined settlementId: {}", settlement.getId());
                settlementTransactionExecutor.resolveTransfer(settlement.getId(), outcome.errorCode(), outcome.errorDescription());
            }
        }
    }
}
