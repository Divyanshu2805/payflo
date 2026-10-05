package com.project.payflo.common_lib.enums;

/**
 * Stored by name in payment.method, which a check constraint restricts to these names. Adding a method needs a
 * Flyway migration that widens payment_method_check; PaymentMethodTest fails until it is written. Renaming or
 * removing one changes the meaning of existing rows, so it needs a data migration as well.
 */
public enum PaymentMethod {
    CARD,
    NETBANKING,
    UPI,
    WALLET,
}
