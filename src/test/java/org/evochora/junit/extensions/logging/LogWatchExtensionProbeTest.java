package org.evochora.junit.extensions.logging;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests that LogWatchExtension takes a question whether a level is enabled for no log.
 * <p>
 * Logback passes such a question through the extension's filter like a log call, whatever level
 * the logger has, so the test does not depend on the logging configuration.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class LogWatchExtensionProbeTest {

    private static final Logger logger = LoggerFactory.getLogger(LogWatchExtensionProbeTest.class);

    /**
     * Asks for WARN and ERROR without logging. The check is the extension's own after the test:
     * it fails the test if it took either question for a log.
     */
    @Test
    void questionWhetherALevelIsEnabledIsNoLog() {
        logger.isWarnEnabled();
        logger.isErrorEnabled();
    }
}
