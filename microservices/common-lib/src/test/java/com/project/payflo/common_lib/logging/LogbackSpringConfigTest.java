package com.project.payflo.common_lib.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

// Loads the real logback-spring.xml (Boot's console appender and pattern, with our conversion words) and logs through
// it, so a typo in the file or a Boot change that stops the override from taking effect fails here, not in production.
class LogbackSpringConfigTest {

    private static final String PAN = "4242424242424242";

    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private LoggerContext context;

    @BeforeEach
    void configureFromTheShippedFile() throws Exception {
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        URL config = getClass().getClassLoader().getResource("logback-spring.xml");
        assertThat(config).as("common-lib ships logback-spring.xml").isNotNull();

        context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter()); // a bare context has none, and building a log event asks for it
        // Boot's own pattern needs values Boot sets at runtime (the application name, the correlation id), which a bare
        // Logback context doesn't have. Boot reads the pattern from this property, so a plain one still goes through the
        // file's %m and %wEx overrides, which is what is under test.
        context.putProperty("CONSOLE_LOG_PATTERN", "%5p %logger{0} : %m%n%wEx");
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(config);
    }

    @AfterEach
    void restore() {
        context.stop();
        System.setOut(originalOut);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    // Logback's own account of loading the file, shown when an assertion fails
    private String status() {
        StringBuilder text = new StringBuilder();
        context.getStatusManager().getCopyOfStatusList().forEach(s -> text.append(s.getLevel()).append(' ').append(s.getMessage()).append(s.getThrowable() == null ? "" : " <- " + s.getThrowable()).append(System.lineSeparator()));
        return text.toString();
    }

    @Test
    void aCardNumberLoggedThroughTheShippedConfigurationIsMasked() {
        Logger log = context.getLogger("payment");

        log.info("Charging card {} for order {}", PAN, "o-1");

        assertThat(output()).as(this::status).doesNotContain(PAN).contains("[PAN ****4242]").contains("order o-1");
    }

    @Test
    void aCardNumberInAnExceptionIsMaskedInTheShippedConfigurationToo() {
        context.getLogger("payment").error("Charge failed", new IllegalStateException("could not parse " + PAN));

        assertThat(output()).as(this::status).doesNotContain(PAN).contains("[PAN ****4242]").contains("IllegalStateException");
    }

    @Test
    void anOrdinaryLineIsLoggedUnchanged() {
        context.getLogger("payment").info("Payment {} captured", "01a10c7a");

        assertThat(output()).as(this::status).contains("INFO").contains("payment : Payment 01a10c7a captured");
    }
}
