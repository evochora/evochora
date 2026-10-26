package org.evochora.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;

/**
 * The logging configuration the application ships, {@code logback.xml}, as Logback builds it.
 */
@Tag("integration")
class LogbackConfigurationTest {

    /**
     * Logs are diagnostics and go to standard error, so that standard output carries only a
     * command's result: {@code inspect storage -f json} is piped on, and a log line ahead of the
     * JSON breaks the pipe.
     */
    @Test
    void theShippedConsoleAppendersWriteToStandardError() throws Exception {
        for (String name : new String[] {"CONSOLE", "CONSOLE_PLAIN"}) {
            // The format property names the appender the configuration attaches; set on the
            // context, it takes precedence over the system property other tests leave behind
            final LoggerContext context = new LoggerContext();
            context.putProperty("evochora.logging.format", name);
            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(getClass().getClassLoader().getResource("logback.xml"));
            try {
                final Appender<?> appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(name);
                assertNotNull(appender, name + " is attached");
                assertEquals("System.err", ((ConsoleAppender<?>) appender).getTarget(), name);
            } finally {
                context.stop();
            }
        }
    }
}
