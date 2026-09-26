package com.project.payflo.payment_service.validation;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.payment_service.dto.request.CreateOrderRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderAmountTest {

    private static final jakarta.validation.ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static List<String> violations(Money amount) {
        return VALIDATOR.validate(new CreateOrderRequest(amount, null, null, null, null)).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
    }

    @Test
    void acceptsAnOrdinaryAmount() {
        assertThat(violations(Money.inr(44_550))).isEmpty();
        assertThat(violations(Money.inr(1))).isEmpty();
        assertThat(violations(Money.inr(OrderAmountValidator.MAX_AMOUNT_UNITS))).isEmpty();
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThat(violations(Money.inr(0))).hasSize(1);
        assertThat(violations(Money.inr(-500))).hasSize(1);
        assertThat(violations(Money.inr(Integer.MIN_VALUE))).hasSize(1);
    }

    @Test
    void rejectsAnAmountAboveTheLimit() {
        assertThat(violations(Money.inr(OrderAmountValidator.MAX_AMOUNT_UNITS + 1))).hasSize(1);
        assertThat(violations(Money.inr(Integer.MAX_VALUE))).hasSize(1);
    }

    @Test
    void rejectsAnUnsupportedOrMissingCurrency() {
        assertThat(violations(Money.of(1000, "USD"))).hasSize(1);
        assertThat(violations(Money.of(1000, "inr"))).hasSize(1);
        assertThat(violations(Money.of(1000, ""))).hasSize(1);
        assertThat(violations(Money.of(1000, null))).hasSize(1);
    }

    @Test
    void aMissingAmountIsReportedOnceByNotNull() {
        assertThat(violations(null)).containsExactly("amount: Amount is required");
    }
}
