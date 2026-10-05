package com.project.payflo.common_lib.logging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field, record component or parameter that holds a card number (PAN) or card security code.
 *
 * <p>It is the declaration that something is card data. {@link CardMasker#describe(Object)} reads it to build a
 * {@code toString()} that never prints the value, and a type that carries card data should use that for its
 * {@code toString()}. Whatever slips past, the Logback converters in this package mask any card number found in a
 * log line or stack trace, so a card number can't reach the logs even if nobody remembered to annotate.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD})
public @interface MaskedCard {

    /**
     * {@code false} (the default) keeps the last four digits, {@code ****1111}, as PCI DSS allows for display.
     * {@code true} hides the value entirely, which is what a CVV needs: it must never be shown, not even in part.
     */
    boolean full() default false;
}
