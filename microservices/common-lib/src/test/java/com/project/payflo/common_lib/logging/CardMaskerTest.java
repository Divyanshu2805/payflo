package com.project.payflo.common_lib.logging;

import com.project.payflo.common_lib.dto.PaymentProcessorRequest;
import com.project.payflo.common_lib.entity.Money;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardMaskerTest {

    private static final String VISA = "4111111111111111";   // passes Luhn
    private static final String MASTERCARD = "5555555555554444";
    private static final String AMEX = "378282246310005";    // 15 digits

    @Test
    void aCardNumberInAMessageIsMaskedToItsLastFour() {
        assertThat(CardMasker.maskAll("charging card " + VISA + " for 500"))
                .isEqualTo("charging card [PAN ****1111] for 500");
    }

    @Test
    void groupedCardNumbersAreMaskedToo() {
        assertThat(CardMasker.maskAll("pan=4111 1111 1111 1111;")).isEqualTo("pan=[PAN ****1111];");
        assertThat(CardMasker.maskAll("pan=4111-1111-1111-1111")).isEqualTo("pan=[PAN ****1111]");
        assertThat(CardMasker.maskAll(AMEX)).isEqualTo("[PAN ****0005]");
    }

    @Test
    void everyCardNumberInALineIsMasked() {
        assertThat(CardMasker.maskAll(VISA + " then " + MASTERCARD))
                .isEqualTo("[PAN ****1111] then [PAN ****4444]");
    }

    @Test
    void numbersThatAreNotCardNumbersAreLeftAlone() {
        assertThat(CardMasker.maskAll("epoch 1759675260123 done")).isEqualTo("epoch 1759675260123 done");     // starts with 1
        assertThat(CardMasker.maskAll("id 4111111111111112")).isEqualTo("id 4111111111111112");                // fails Luhn
        assertThat(CardMasker.maskAll("at 2026-10-05 20:21:00")).isEqualTo("at 2026-10-05 20:21:00");          // a timestamp
        assertThat(CardMasker.maskAll("payment 01a10c7a-9f3e-7bcb-93a2-b3e532e8b52f")).isEqualTo("payment 01a10c7a-9f3e-7bcb-93a2-b3e532e8b52f");
        assertThat(CardMasker.maskAll("amountUnits=500000000 and 123456789012")).isEqualTo("amountUnits=500000000 and 123456789012"); // too short
        assertThat(CardMasker.maskAll("41111111111111111111")).isEqualTo("41111111111111111111");               // 20 digits
    }

    @Test
    void anUnmaskedLineIsReturnedAsIsAndNullIsSafe() {
        String line = "nothing to hide here, just a very long ordinary message";
        assertThat(CardMasker.maskAll(line)).isSameAs(line);
        assertThat(CardMasker.maskAll(null)).isNull();
        assertThat(CardMasker.maskAll("")).isEmpty();
    }

    @Test
    void maskKeepsOnlyTheLastFour() {
        assertThat(CardMasker.mask(VISA)).isEqualTo("****1111");
        assertThat(CardMasker.mask("4111 1111 1111 1111")).isEqualTo("****1111");
        assertThat(CardMasker.mask("12")).isEqualTo("***");
        assertThat(CardMasker.mask(null)).isEqualTo("null");
    }

    record Sample(@MaskedCard String pan, @MaskedCard(full = true) String cvv, String name) {
    }

    @Test
    void describePrintsAnAnnotatedRecordWithoutItsCardData() {
        String text = CardMasker.describe(new Sample(VISA, "123", "Asha Rao"));

        assertThat(text).isEqualTo("Sample[pan=****1111, cvv=***, name=Asha Rao]");
        assertThat(text).doesNotContain(VISA).doesNotContain("123");
    }

    static class Plain {
        @MaskedCard private final String number = MASTERCARD;
        private final String label = "work card";
    }

    @Test
    void describeWorksOnAPlainClassToo() {
        assertThat(CardMasker.describe(new Plain())).isEqualTo("Plain[number=****4444, label=work card]");
    }

    @Test
    void theProcessorRequestNeverPrintsTheCardNumber() {
        PaymentProcessorRequest request = PaymentProcessorRequest.card(UUID.randomUUID(), VISA, "12/31", Money.inr(500), Map.of());

        assertThat(request.toString()).doesNotContain(VISA).contains("****1111").contains("12/31");
    }
}
