package com.project.payflo.common_lib.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback's {@code %m}, with any card number in the formatted message masked. See {@link CardMasker}. */
public class MaskedMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return CardMasker.maskAll(super.convert(event));
    }
}
