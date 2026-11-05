package org.evochora.node.processes.http.api.visualizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.sql.SQLException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests how {@link VisualizerBaseController} describes a failure for its log line.
 */
@Tag("unit")
class VisualizerBaseControllerTest {

    @Test
    void describesEveryLinkOfTheChainOutermostFirst() {
        SQLException root = new SQLException("table missing");
        IOException middle = new IOException("read failed", root);
        RuntimeException outer = new RuntimeException("request failed", middle);

        assertEquals("RuntimeException: request failed <- IOException: read failed <- SQLException: table missing",
                VisualizerBaseController.describeCauses(outer));
    }

    @Test
    void describesAChainThatComesRoundAgainOnceEachLink() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);

        assertEquals("IllegalStateException: first <- IllegalStateException: second",
                VisualizerBaseController.describeCauses(first));
    }
}
