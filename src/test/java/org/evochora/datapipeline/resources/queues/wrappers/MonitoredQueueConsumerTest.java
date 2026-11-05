package org.evochora.datapipeline.resources.queues.wrappers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.channels.ClosedByInterruptException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.ConfigFactory;

/**
 * Tests how {@link MonitoredQueueConsumer} logs a failed receive: a failure that follows from an
 * interrupt is a stop and logs no error, every other failure does.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class MonitoredQueueConsumerTest {

    /** A queue whose receive fails with the given exception. */
    private static final class FailingQueue extends InMemoryBlockingQueue<String> {
        private final RuntimeException failure;

        FailingQueue(RuntimeException failure) {
            super("failing-queue", ConfigFactory.empty());
            this.failure = failure;
        }

        @Override
        public StreamingBatch<String> receiveBatch(int maxSize, long timeout, TimeUnit unit) {
            throw failure;
        }
    }

    private static MonitoredQueueConsumer<String> consumerOf(RuntimeException failure) {
        return new MonitoredQueueConsumer<>(new FailingQueue(failure),
                new ResourceContext("service", "input", "queue-in", "failing-queue", Map.of()));
    }

    @Test
    void aFailureThatFollowsFromAnInterruptIsPassedOnWithoutAnError() {
        RuntimeException failure = new RuntimeException("read failed", new ClosedByInterruptException());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> consumerOf(failure).receiveBatch(1, 0, TimeUnit.SECONDS));

        assertSame(failure, thrown);
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "receiveBatch failed: service=service, queue=failing-queue, error=read failed")
    void anyOtherFailureIsLoggedAsAnErrorAndPassedOn() {
        RuntimeException failure = new RuntimeException("read failed", new java.io.IOException("disk gone"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> consumerOf(failure).receiveBatch(1, 0, TimeUnit.SECONDS));

        assertSame(failure, thrown);
    }
}
