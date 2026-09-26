package com.project.payflo.common_lib.util;

import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Literal IPs only, so nothing here depends on DNS.
class WebhookUrlValidatorTest {

    private final WebhookUrlValidator strict = new WebhookUrlValidator(false);
    private final WebhookUrlValidator dev = new WebhookUrlValidator(true);

    @Test
    void acceptsAPublicHttpsAddress() {
        assertThatCode(() -> strict.validate("https://93.184.216.34/hooks/payflo")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook",            // loopback
            "https://10.1.2.3/hook",             // private
            "https://172.16.0.5/hook",
            "https://192.168.1.10/hook",
            "https://100.64.0.1/hook",           // carrier-grade NAT
            "https://[::1]/hook",                // IPv6 loopback
            "https://[fc00::1]/hook",            // IPv6 unique local
            "https://0.0.0.0/hook",
            "https://169.254.169.254/latest/meta-data",  // cloud metadata (link-local)
            "https://[::ffff:10.0.0.1]/hook"     // IPv4-mapped private address
    })
    void refusesNonPublicAddressesInStrictMode(String url) {
        assertThatThrownBy(() -> strict.validate(url))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessRuleViolationException) e).getErrorCode())
                .isEqualTo(WebhookUrlValidator.ERROR_CODE);
    }

    @Test
    void refusesPlainHttpInStrictMode() {
        assertThatThrownBy(() -> strict.validate("http://93.184.216.34/hook"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void devModeAllowsLoopbackPrivateAndPlainHttp() {
        assertThatCode(() -> dev.validate("http://127.0.0.1:8080/webhook/success")).doesNotThrowAnyException();
        assertThatCode(() -> dev.validate("http://10.0.0.5/hook")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data",
            "https://169.254.169.254/latest/meta-data",
            "https://0.0.0.0/hook",
            "https://224.0.0.1/hook"
    })
    void devModeStillRefusesLinkLocalUnspecifiedAndMulticast(String url) {
        assertThatThrownBy(() -> dev.validate(url)).isInstanceOf(BusinessRuleViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://93.184.216.34/hook",
            "file:///etc/passwd",
            "https://user:pass@93.184.216.34/hook",   // embedded credentials
            "https:///no-host",
            "not a url",
            ""
    })
    void refusesMalformedOrUnsupportedUrls(String url) {
        assertThatThrownBy(() -> dev.validate(url)).isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void refusesAHostThatDoesNotResolve() {
        assertThat(catchOf(() -> strict.validate("https://no-such-host.invalid/hook")))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    private static Throwable catchOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
