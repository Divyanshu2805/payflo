package com.project.payflo.vault_service.dto.request;

import com.project.payflo.common_lib.logging.CardMasker;
import com.project.payflo.common_lib.logging.MaskedCard;
import com.project.payflo.vault_service.validation.ExpiryYear;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.LuhnCheck;

import java.util.UUID;

public record TokenizeRequest(

        @NotBlank(message = "PAN is required")
        @LuhnCheck(message = "Invalid card number")
        @Pattern(regexp = "^[0-9]{13,19}$", message = "PAN length is invalid")
        @MaskedCard
        String pan,

        @NotBlank(message = "CVV is required")
        @Pattern(regexp = "^[0-9]{3,4}$", message = "CVV length is invalid")
        @MaskedCard(full = true)
        String cvv,

        @NotNull(message = "Expiry month is required")
        @Min(value = 1, message = "Expiry must be between 1 to 12")
        @Max(value = 12, message = "Expiry must be between 1 to 12")
        Integer expiryMonth,

        @NotNull(message = "Expiry year is required")
        @ExpiryYear
        Integer expiryYear,

        UUID customerId,

        @Size(min = 3, message = "Card Holder Name should have at least 3 characters")
        String cardHolderName

) {

    // A record prints every component by default: this one never prints the card number or the CVV.
    @Override
    public String toString() {
        return CardMasker.describe(this);
    }
}
