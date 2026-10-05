package com.project.payflo.payment_service.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentTest {

    @Test
    void errorFieldsAreCutToTheirColumnWidthSoAnOverlongMessageCannotBreakTheSave() {
        Payment payment = new Payment();

        payment.setErrorCode("C".repeat(300));
        payment.setErrorDescription("D".repeat(300));

        assertThat(payment.getErrorCode()).hasSize(100);
        assertThat(payment.getErrorDescription()).hasSize(255);
    }

    @Test
    void shortAndMissingValuesAreKeptAsTheyAre() {
        Payment payment = new Payment();

        payment.setErrorCode("CARD_DECLINED");
        payment.setErrorDescription(null);

        assertThat(payment.getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(payment.getErrorDescription()).isNull();
    }
}
