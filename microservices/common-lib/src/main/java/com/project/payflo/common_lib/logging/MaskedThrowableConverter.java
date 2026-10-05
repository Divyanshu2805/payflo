package com.project.payflo.common_lib.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * Spring Boot's {@code %wEx}, with any card number in the stack trace masked: an exception message can carry the
 * value that failed to parse or validate. See {@link CardMasker}.
 */
public class MaskedThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy proxy) {
        return CardMasker.maskAll(super.throwableProxyToString(proxy));
    }
}
