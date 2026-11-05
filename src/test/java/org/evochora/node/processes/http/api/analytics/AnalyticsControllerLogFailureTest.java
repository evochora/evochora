package org.evochora.node.processes.http.api.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.evochora.datapipeline.api.resources.OperationalException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * A failed request is logged on one line when its cause is known, and with its stack trace when
 * it may have met a defect.
 */
@Tag("unit")
class AnalyticsControllerLogFailureTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("test.analytics.logFailure");
    private final Recorder recorder = new Recorder();

    /**
     * Sees the call of the test's logger before any other filter and keeps it out of the log.
     * Logback hands a trailing throwable to the filter as the last parameter, not yet as {@code t}.
     */
    private final class Recorder extends TurboFilter {
        volatile String message;
        volatile Throwable throwable;

        @Override
        public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                                  Object[] params, Throwable t) {
            if (logger != AnalyticsControllerLogFailureTest.this.logger || format == null) {
                return FilterReply.NEUTRAL;
            }
            var formatted = MessageFormatter.arrayFormat(format, params);
            message = formatted.getMessage();
            throwable = t != null ? t : formatted.getThrowable();
            return FilterReply.DENY;
        }
    }

    @BeforeEach
    void attach() {
        recorder.start();
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getTurboFilterList().add(0, recorder);
    }

    @AfterEach
    void detach() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getTurboFilterList().remove(recorder);
    }

    @Test
    void aKnownCauseIsLoggedOnOneLineWithoutStackTrace() {
        AnalyticsController.logFailure(logger, "Query failed for {}",
            new RuntimeException("wrapped", new OperationalException("storage gone",
                new java.io.IOException("disk unmounted"))), "run-1");

        assertEquals("Query failed for run-1: storage gone; caused by: disk unmounted", recorder.message);
        assertNull(recorder.throwable, "no stack trace for a known cause");
    }

    @Test
    void anyOtherFailureIsLoggedWithItsStackTrace() {
        IllegalStateException defect = new IllegalStateException("defect");
        AnalyticsController.logFailure(logger, "Query failed for {}", defect, "run-1");

        assertEquals("Query failed for run-1", recorder.message);
        assertNotNull(recorder.throwable, "the stack trace of a possible defect");
    }
}
