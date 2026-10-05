package com.project.payflo.merchant_service.dto.request;

import com.project.payflo.common_lib.enums.BusinessType;
import com.project.payflo.common_lib.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// The request bodies of the merchant profile, payout account and user endpoints, kept together.
public final class MerchantProfileRequests {

    private MerchantProfileRequests() {
    }

    // Every field is optional: only the ones sent are changed.
    public record UpdateProfileRequest(
            @Size(min = 1, max = 50, message = "name must be 1 to 50 characters long")
            String name,

            @Size(min = 1, max = 50, message = "businessName must be 1 to 50 characters long")
            String businessName,

            BusinessType businessType,

            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "contactNumber must be 7 to 20 digits, spaces, + or -")
            String contactNumber,

            @Size(max = 200)
            @Pattern(regexp = "^https?://.+", message = "websiteUrl must be a valid http(s) URL")
            String websiteUrl,

            @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", message = "gstId is not a valid GSTIN")
            String gstId,

            @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "panId is not a valid PAN")
            String panId
    ) {}

    public record SettlementBankRequest(
            @NotBlank(message = "accountNumber is required")
            @Pattern(regexp = "^[0-9]{9,18}$", message = "accountNumber must be 9 to 18 digits")
            String accountNumber,

            @NotBlank(message = "ifsc is required")
            @Pattern(regexp = "^[A-Z]{4}0[A-Z0-9]{6}$", message = "ifsc is not a valid IFSC code")
            String ifsc,

            @NotBlank(message = "accountHolderName is required")
            @Size(max = 200)
            String accountHolderName,

            // Changing where the money goes needs the password again, even with a valid login.
            @NotBlank(message = "currentPassword is required")
            String currentPassword
    ) {}

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 200)
            String email,

            @NotBlank(message = "password is required")
            @Size(min = 8, max = 100, message = "password must be 8 to 100 characters long")
            String password,

            @NotNull(message = "role is required")
            UserRole role
    ) {}

    public record UpdateUserRoleRequest(
            @NotNull(message = "role is required")
            UserRole role
    ) {}
}
