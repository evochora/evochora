package org.evochora.junit.extensions.logging;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.evochora.junit.extensions.logging.LogLevel.ERROR;
import static org.evochora.junit.extensions.logging.LogLevel.WARN;

/**
 * Tests the isolation between tests for LogWatchExtension.
 * <p>
 * Each test method logs different messages with different levels.
 * The extension should properly isolate events and rules between tests.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class LogWatchExtensionIsolationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(LogWatchExtensionIsolationTest.class);
    
    /**
     * Test 1: Expects an ERROR log with specific message.
     * Should NOT see any logs from other tests.
     */
    @Test
    @ExpectLog(level = ERROR, 
               loggerPattern = "org.evochora.junit.extensions.logging.LogWatchExtensionIsolationTest",
               messagePattern = "Test1: Expected error message")
    void test1_ExpectsSpecificError() {
        logger.info("Test1: This info should be ignored");
        logger.error("Test1: Expected error message");
        logger.info("Test1: Another info");
    }
    
    /**
     * Test 2: Expects a WARN log with specific message.
     * Should NOT see the ERROR from test1.
     */
    @Test
    @ExpectLog(level = WARN,
               loggerPattern = "org.evochora.junit.extensions.logging.LogWatchExtensionIsolationTest",
               messagePattern = "Test2: Expected warning")
    void test2_ExpectsSpecificWarning() {
        logger.info("Test2: Some info log");
        logger.warn("Test2: Expected warning");
        logger.info("Test2: More info");
        
        // If isolation is broken, we might see "Test1: Expected error message" here
        // which would cause this test to fail
    }
    
    /**
     * Test 3: Allows a specific WARN, but should fail if ERROR appears.
     * Tests that previous test's ERROR doesn't leak into this test.
     */
    @Test
    @AllowLog(level = WARN,
              loggerPattern = "org.evochora.junit.extensions.logging.LogWatchExtensionIsolationTest",
              messagePattern = "Test3: Allowed warning")
    void test3_AllowsWarningButNotError() {
        logger.info("Test3: Info message");
        logger.warn("Test3: Allowed warning");
        
        // If test1's ERROR leaked here, this test would fail
        // because ERROR is not allowed by this test's rules
    }
    
    /**
     * Test 4: Expects multiple occurrences of the same log.
     * Tests that event counting is properly reset between tests.
     */
    @Test
    @ExpectLog(level = WARN,
               loggerPattern = "org.evochora.junit.extensions.logging.LogWatchExtensionIsolationTest",
               messagePattern = "Test4: Multiple warning",
               occurrences = 3)
    void test4_ExpectsMultipleOccurrences() {
        logger.warn("Test4: Multiple warning");
        logger.warn("Test4: Multiple warning");
        logger.warn("Test4: Multiple warning");
        
        // Should expect exactly 3 occurrences, not more from other tests
    }
    
    /**
     * Test 5: No logs expected at WARN level or above.
     * Tests that previous test's warnings don't leak.
     */
    @Test
    void test5_ExpectsNoWarningsOrErrors() {
        logger.info("Test5: Only info logs here");
        logger.debug("Test5: Debug log");
        
        // If test4's warnings leaked, this would fail
    }
    
    /**
     * Test 6: Complex scenario with multiple rules.
     */
    @Test
    @ExpectLog(level = ERROR,
               loggerPattern = "org.evochora.junit.extensions.logging.LogWatchExtensionIsolationTest",
               messagePattern = "Test6: Critical error")
    @AllowLog(level = WARN,
              loggerPattern = "org.evochora.junit.extensions.logging.LogWatchExtensionIsolationTest",
              messagePattern = "Test6: Known warning")
    void test6_ComplexRules() {
        logger.info("Test6: Starting test");
        logger.warn("Test6: Known warning");
        logger.error("Test6: Critical error");
        logger.info("Test6: Finishing test");
        
        // Should handle both ExpectLog and AllowLog correctly
        // And not be affected by any previous test's logs
    }
}

