package com.project.payflo.merchant_service.security;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallerPolicyAndCipherTest {

    // ---- who may do what

    private final MerchantContext context = new MerchantContext();
    private final CallerPolicy policy = new CallerPolicy(context);

    @Test
    void anApiKeyIsNotADashboardUser() {
        assertThatThrownBy(policy::requireDashboardUser).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("DASHBOARD_LOGIN_REQUIRED"));
    }

    @Test
    void aDashboardUserIsIdentifiedByEmail() {
        context.setUserEmail("owner@example.com");
        context.setUserRole("OWNER");

        assertThat(policy.requireDashboardUser()).isEqualTo("owner@example.com");
    }

    @Test
    void theRoleMustBeOneOfTheAllowedOnes() {
        context.setUserEmail("a@example.com");
        context.setUserRole("ADMIN");

        assertThatCode(() -> policy.requireRole(UserRole.OWNER, UserRole.ADMIN)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireRole(UserRole.OWNER)).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("ROLE_FORBIDDEN"));
    }

    @Test
    void anUnknownRoleIsNeverAllowed() {
        context.setUserEmail("a@example.com");
        context.setUserRole("SUPERUSER");

        assertThatThrownBy(() -> policy.requireRole(UserRole.OWNER, UserRole.ADMIN, UserRole.TEAM))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- the payout account cipher

    private final BankAccountCipher cipher = new BankAccountCipher(Encryptors.stronger("test-password", "deadbeef"));

    @Test
    void anAccountNumberRoundTripsButIsNotStoredInTheClear() {
        String stored = cipher.encrypt("123456789012");

        assertThat(stored).startsWith("enc1:").doesNotContain("123456789012");
        assertThat(cipher.decrypt(stored)).isEqualTo("123456789012");
    }

    @Test
    void theSameNumberEncryptsDifferentlyEachTime() {
        assertThat(cipher.encrypt("123456789012")).isNotEqualTo(cipher.encrypt("123456789012"));
    }

    @Test
    void aValueWrittenBeforeEncryptionExistedIsStillReadable() {
        assertThat(cipher.decrypt("123456789012")).isEqualTo("123456789012");
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void maskingShowsOnlyTheLastFour() {
        assertThat(BankAccountCipher.mask("123456789012")).isEqualTo("XXXXXXXX9012");
        assertThat(BankAccountCipher.mask("1234")).isEqualTo("1234");
        assertThat(BankAccountCipher.mask(null)).isNull();
    }
}
