package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.ChaosMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankTransferProcessorImplTest {

    private final PayoutSimulatorProperties properties = new PayoutSimulatorProperties();
    private final BankTransferProcessorImpl processor = new BankTransferProcessorImpl(new PayoutOutcomeDecider(properties));

    @Test
    void anAcceptedTransferGetsAReference() {
        assertThat(processor.initiate(UUID.randomUUID(), UUID.randomUUID(), Money.inr(1_000), "123456789012", "HDFC0001234")
                .registrationRef()).startsWith("TXN_");
    }

    @Test
    void aRefusedTransferIsRejectedBeforeAnythingIsPaid() {
        properties.setRefuseRate(100);

        assertThatThrownBy(() -> processor.initiate(UUID.randomUUID(), UUID.randomUUID(), Money.inr(1_000), "123456789012", "HDFC0001234"))
                .isInstanceOf(BankTransferRefusedException.class);
    }

    @Test
    void successModeNeverRefuses() {
        properties.setRefuseRate(100);
        properties.setChaosMode(ChaosMode.SUCCESS);

        assertThat(processor.initiate(UUID.randomUUID(), UUID.randomUUID(), Money.inr(1_000), "123456789012", "HDFC0001234")
                .registrationRef()).isNotBlank();
    }
}
