package com.project.payflo.common_lib.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMethodTest {

    // payment.method holds the constant's name, and the table's payment_method_check lists the allowed names. Adding a
    // method means a Flyway migration that widens that constraint (see V2__payment_method_as_name.sql), so this fails
    // until you write one, and then until you update the list below with it.
    @Test
    void theNamesMatchWhatTheDatabaseConstraintAllows() {
        assertThat(Arrays.stream(PaymentMethod.values()).map(Enum::name))
                .containsExactlyInAnyOrder("CARD", "NETBANKING", "UPI", "WALLET");
    }
}
