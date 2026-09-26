package com.project.payflo.payment_service.validation;

import com.project.payflo.common_lib.entity.Money;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

public class OrderAmountValidator implements ConstraintValidator<OrderAmount, Money> {

    // Amounts are in the smallest unit (paise). 500,000,000 is INR 5,000,000.
    static final int MAX_AMOUNT_UNITS = 500_000_000;
    static final Set<String> SUPPORTED_CURRENCIES = Set.of("INR");

    @Override
    public boolean isValid(Money amount, ConstraintValidatorContext context) {
        if (amount == null) {
            return true; // presence is @NotNull's job
        }

        context.disableDefaultConstraintViolation();

        if (amount.getAmountUnits() < 1 || amount.getAmountUnits() > MAX_AMOUNT_UNITS) {
            context.buildConstraintViolationWithTemplate(
                    "Amount must be between 1 and " + MAX_AMOUNT_UNITS + " (smallest currency unit)")
                    .addConstraintViolation();
            return false;
        }

        if (amount.getCurrency() == null || !SUPPORTED_CURRENCIES.contains(amount.getCurrency())) {
            context.buildConstraintViolationWithTemplate(
                    "Currency must be one of " + SUPPORTED_CURRENCIES)
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
