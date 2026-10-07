package com.project.payflo.operations_service;

import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.ChaosMode;
import com.project.payflo.operations_service.settlement.SettlementEngine;
import com.project.payflo.operations_service.settlement.SettlementRecorder;
import com.project.payflo.operations_service.settlement.SettlementRecoveryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Settlement on a real database, and every way it can be interrupted. The nightly run, the simulated bank's callback
 * and the recovery job all run for real; payment-service and merchant-service are stubs, so a test can take either of
 * them down at the worst moment.
 */
class SettlementIntegrationTest extends OperationsIntegrationTest {

    @Autowired
    private SettlementEngine engine;

    @Autowired
    private SettlementRecoveryJob recovery;

    @Autowired
    private SettlementRecorder recorder;

    private final UUID merchant = UUID.randomUUID();
    private final UUID paymentA = UUID.randomUUID();
    private final UUID paymentB = UUID.randomUUID();

    @BeforeEach
    void aMerchantWithTwoCapturedPaymentsAndABankAccount() {
        when(merchantClient.listActiveMerchantIds()).thenReturn(List.of(merchant));
        when(merchantClient.getSettlementBankDetails(merchant)).thenReturn(new SettlementBankDetails("123456789012", "HDFC0001234", "Holder"));
        // B was refunded 20,000 by the bank; both are still unsettled as far as payment-service is concerned
        when(paymentClient.findUnsettledCaptured(eq(merchant), any(), eq(0), anyInt())).thenReturn(List.of(
                new PaymentSettlementView(paymentA, 100_000, 0, "INR"),
                new PaymentSettlementView(paymentB, 50_000, 20_000, "INR")));
    }

    // ---- helpers

    // Scheduled jobs hold a ShedLock for at least ten seconds after every run, and a call that finds the lock held is
    // skipped without a word. Tests call the job themselves, so each call first drops the lock a previous one left.
    private void recoverNow() {
        redis.delete("job-lock:default:operations-service-settlement-recovery");
        recovery.recover();
    }

    private List<Map<String, Object>> settlements() {
        return jdbc.queryForList("select * from settlement where merchant_id = ? order by created_at", merchant);
    }

    private String onlySettlementStatus() {
        List<Map<String, Object>> all = settlements();
        return all.size() == 1 ? all.getFirst().get("status").toString() : "count=" + all.size();
    }

    private void awaitSettlementStatus(String expected) {
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(onlySettlementStatus()).isEqualTo(expected));
    }

    private int outboxEvents(String eventType) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_type = ? and payload::text like ?", Integer.class,
                eventType, "%" + merchant + "%");
    }

    private UUID settlementId() {
        return (UUID) settlements().getFirst().get("id");
    }

    // ---- the amounts, and the happy path

    @Test
    void aSettlementPaysTheMerchantGrossLessRefundsFeeAndGstAndMarksEveryPaymentSettled() {
        engine.run();

        awaitSettlementStatus("PROCESSED");
        Map<String, Object> s = settlements().getFirst();
        assertThat(s.get("gross_amount_units")).isEqualTo(150_000);
        assertThat(s.get("refund_amount_units")).isEqualTo(20_000);
        assertThat(s.get("fee_amount_units")).isEqualTo(2_600);     // 2% of the 130,000 the merchant kept
        assertThat(s.get("gst_amount_units")).isEqualTo(468);       // 18% of the fee
        assertThat(s.get("net_amount_units")).isEqualTo(126_932);   // 130,000 - 2,600 - 468
        assertThat(s.get("bank_reference").toString()).startsWith("TXN_");

        // the audit trail from the payout back to its payments
        assertThat(jdbc.queryForList("select payment_id from settlement_payment where settlement_id = ?", UUID.class, settlementId()))
                .containsExactlyInAnyOrder(paymentA, paymentB);

        // payment-service is told, once, which payments were paid out
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<UUID>> marked = ArgumentCaptor.forClass(List.class);
            verify(paymentClient).markSettled(marked.capture());
            assertThat(marked.getValue()).containsExactlyInAnyOrder(paymentA, paymentB);
        });
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(settlements().getFirst().get("payments_settled_at")).isNotNull());
        assertThat(outboxEvents("SETTLEMENT_PROCESSED")).isEqualTo(1);
    }

    @Test
    void thePayoutIsNeverMadeTwiceForPaymentsAlreadyInAnUnfinishedSettlement() {
        simulator.setChaosMode(ChaosMode.TIMEOUT); // the first payout stays with the bank

        engine.run();
        engine.run();
        engine.run();

        assertThat(settlements()).hasSize(1);
        assertThat(onlySettlementStatus()).isEqualTo("TRANSFER_PENDING");
    }

    // ---- the bank says no

    @Test
    void aDeclinedPayoutLeavesThePaymentsCapturedTellsTheMerchantAndIsPaidByTheNextRun() {
        simulator.setChaosMode(ChaosMode.FAILURE);
        engine.run();

        awaitSettlementStatus("FAILED");
        assertThat(settlements().getFirst().get("failure_reason").toString()).startsWith("SIM_PAYOUT_DECLINED");
        verify(paymentClient, never()).markSettled(any());
        assertThat(outboxEvents("SETTLEMENT_FAILED")).isEqualTo(1);

        // the money is still owed, so the next run pays it
        simulator.setChaosMode(ChaosMode.SUCCESS);
        engine.run();

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(settlements()).extracting(s -> s.get("status")).containsExactly("FAILED", "PROCESSED"));
        verify(paymentClient, times(1)).markSettled(any());
    }

    @Test
    void aTransferTheBankRefusesToAcceptFailsAtOnceWithoutABankReference() {
        simulator.setChaosMode(ChaosMode.NORMAL); // a refusal is not used in SUCCESS mode, which always pays
        simulator.setRefuseRate(100);

        engine.run();

        assertThat(onlySettlementStatus()).isEqualTo("FAILED");
        Map<String, Object> s = settlements().getFirst();
        assertThat(s.get("failure_reason").toString()).startsWith("TRANSFER_NOT_STARTED");
        assertThat(s.get("bank_reference")).isNull();
    }

    // ---- a neighbour is down

    @Test
    void aMerchantWhoseBankDetailsCannotBeLoadedIsSkippedAndNothingIsWritten() {
        when(merchantClient.getSettlementBankDetails(merchant)).thenThrow(new RuntimeException("merchant-service is down"));

        engine.run();

        assertThat(settlements()).isEmpty();
        verify(paymentClient, never()).markSettled(any());
    }

    @Test
    void aMerchantWithNoBankAccountIsSkipped() {
        when(merchantClient.getSettlementBankDetails(merchant)).thenReturn(new SettlementBankDetails(null, null, null));

        engine.run();

        assertThat(settlements()).isEmpty();
    }

    // ---- crashes between steps: the recovery job finishes what was interrupted

    @Test
    void ifPaymentServiceIsDownAfterTheBankPaidThePaymentsAreMarkedLaterAndNothingIsPaidTwice() {
        doThrow(new RuntimeException("payment-service is down")).when(paymentClient).markSettled(any());

        engine.run();

        // the bank paid; marking failed. The settlement is PROCESSED but not finished, and its payments stay held back.
        awaitSettlementStatus("PROCESSED");
        assertThat(settlements().getFirst().get("payments_settled_at")).isNull();
        engine.run();
        assertThat(settlements()).as("the held-back payments are not paid out again").hasSize(1);

        // payment-service comes back; the recovery job (which waits out a grace period) finishes it
        doNothing().when(paymentClient).markSettled(any());
        jdbc.update("update settlement set processed_at = now() - interval '10 minutes' where merchant_id = ?", merchant);
        recoverNow();

        assertThat(settlements().getFirst().get("payments_settled_at")).isNotNull();
        assertThat(settlements()).hasSize(1);
    }

    @Test
    void aSettlementThatCrashedBeforeTheTransferReachedTheBankIsRestartedAndPaidOnce() {
        // what the nightly run leaves behind if the process dies right after step 1
        recorder.createInitiated(merchant, Money.inr(150_000), Money.inr(20_000), Money.inr(2_600), Money.inr(468), Money.inr(126_932),
                List.of(paymentA, paymentB));
        assertThat(onlySettlementStatus()).isEqualTo("INITIATED");
        jdbc.update("update settlement set created_at = now() - interval '10 minutes' where merchant_id = ?", merchant);

        recoverNow();

        awaitSettlementStatus("PROCESSED");
        assertThat(settlements().getFirst().get("bank_reference")).isNotNull();
        assertThat(settlements()).hasSize(1);
    }

    @Test
    void aTransferTheBankNeverAnsweredIsGivenUpOnAndItsPaymentsBecomePayableAgain() {
        simulator.setChaosMode(ChaosMode.TIMEOUT);
        engine.run();
        assertThat(onlySettlementStatus()).isEqualTo("TRANSFER_PENDING");
        jdbc.update("update settlement set updated_at = now() - interval '3 hours' where merchant_id = ?", merchant);

        recoverNow();

        assertThat(settlements().getFirst().get("status")).isEqualTo("FAILED");
        assertThat(settlements().getFirst().get("failure_reason").toString()).startsWith("TRANSFER_TIMEOUT");

        simulator.setChaosMode(ChaosMode.SUCCESS);
        engine.run();
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(settlements()).extracting(s -> s.get("status")).containsExactly("FAILED", "PROCESSED"));
    }

    @Test
    void aRecentlyStartedSettlementIsNotTouchedByTheRecoveryJob() {
        recorder.createInitiated(merchant, Money.inr(150_000), Money.inr(20_000), Money.inr(2_600), Money.inr(468), Money.inr(126_932),
                List.of(paymentA, paymentB));

        recoverNow(); // it was created a moment ago: the nightly run may still be working on it

        assertThat(onlySettlementStatus()).isEqualTo("INITIATED");
    }
}
