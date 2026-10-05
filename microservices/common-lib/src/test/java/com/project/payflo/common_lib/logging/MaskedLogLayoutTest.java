package com.project.payflo.common_lib.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// The same conversion words logback-spring.xml overrides, run through a real Logback layout.
class MaskedLogLayoutTest {

    private static final String PAN = "4111111111111111";

    private final LoggerContext context = new LoggerContext();
    private PatternLayout layout;

    @BeforeEach
    void aLayoutWithTheMaskingConverters() {
        layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern("%level %logger{0} : %m%n%wEx");
        layout.getInstanceConverterMap().put("m", MaskedMessageConverter::new);
        layout.getInstanceConverterMap().put("wEx", MaskedThrowableConverter::new);
        layout.start();
    }

    private String log(String message, Throwable error, Object... arguments) {
        LoggingEvent event = new LoggingEvent(getClass().getName(), context.getLogger("test"), Level.INFO, message, error, arguments);
        return layout.doLayout(event);
    }

    @Test
    void aCardNumberPassedAsALogArgumentNeverReachesTheOutput() {
        String line = log("Charging card {} for order {}", null, PAN, "o-1");

        assertThat(line).doesNotContain(PAN).contains("[PAN ****1111]").contains("order o-1");
    }

    @Test
    void aCardNumberConcatenatedIntoTheMessageIsMaskedToo() {
        assertThat(log("Charging card " + PAN, null)).doesNotContain(PAN).contains("****1111");
    }

    @Test
    void aCardNumberInAnExceptionMessageIsMaskedInTheStackTrace() {
        String line = log("Charge failed", new IllegalStateException("bad number " + PAN));

        assertThat(line).doesNotContain(PAN).contains("IllegalStateException").contains("[PAN ****1111]");
    }

    @Test
    void anOrdinaryLineIsUnchanged() {
        assertThat(log("Payment {} captured", null, "01a10c7a-9f3e")).isEqualTo("INFO test : Payment 01a10c7a-9f3e captured" + System.lineSeparator());
    }
}
