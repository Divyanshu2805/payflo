package com.project.payflo.payment_service.processor;

import com.project.payflo.common_lib.dto.PaymentProcessorRequest;
import com.project.payflo.common_lib.dto.PaymentProcessorResponse;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.payment_service.processor.strategy.WalletPaymentProcessor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletPaymentProcessorTest {

    private final WalletPaymentProcessor processor = new WalletPaymentProcessor();

    private PaymentProcessorResponse charge(Map<String, Object> details) {
        return processor.charge(PaymentProcessorRequest.nonCard(UUID.randomUUID(), PaymentMethod.WALLET, Money.inr(1000), details));
    }

    @Test
    void anOrdinaryWalletIsRegisteredAndLeftForTheBankToResolve() {
        PaymentProcessorResponse response = charge(Map.of("wallet", "PAYTM"));

        assertThat(response).isInstanceOfSatisfying(PaymentProcessorResponse.Pending.class,
                p -> assertThat(p.processorReference()).startsWith("WALLET_PROCESSOR_"));
    }

    @Test
    void theTriggerValueIsRejected() {
        PaymentProcessorResponse response = charge(Map.of("wallet", "wallet_fail"));

        assertThat(response).isInstanceOfSatisfying(PaymentProcessorResponse.Failure.class,
                f -> assertThat(f.errorCode()).isEqualTo("WALLET_REJECTED"));
    }

    @Test
    void missingDetailsDoNotBlowUp() {
        assertThat(charge(null)).isInstanceOf(PaymentProcessorResponse.Pending.class);
    }
}
