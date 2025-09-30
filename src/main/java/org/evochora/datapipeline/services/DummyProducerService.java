package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SystemContracts.DummyMessage;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.wrappers.queues.IOutputQueueResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A dummy producer service that sends Protobuf messages to an output queue.
 * It serves as a test service and a reference implementation.
 *
 * <h3>Configuration Options:</h3>
 * <ul>
 *   <li><b>intervalMs</b>: Milliseconds between messages (default: 1000).</li>
 *   <li><b>messagePrefix</b>: Prefix for the message content (default: "Message").</li>
 *   <li><b>maxMessages</b>: Maximum messages to send, -1 for unlimited (default: -1).</li>
 *   <li><b>throughputWindowSeconds</b>: Time window in seconds for throughput calculation (default: 5).</li>
 * </ul>
 */
public class DummyProducerService extends AbstractService implements IMonitorable {

    private static final Logger logger = LoggerFactory.getLogger(DummyProducerService.class);

    private IOutputQueueResource<DummyMessage> outputQueue;
    private final long intervalMs;
    private final String messagePrefix;
    private final long maxMessages;
    private final int throughputWindowSeconds;

    private final AtomicLong messagesSent = new AtomicLong(0);
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Long> messageTimestamps = new ConcurrentLinkedDeque<>();

    public DummyProducerService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.intervalMs = options.hasPath("intervalMs") ? options.getLong("intervalMs") : 1000L;
        this.messagePrefix = options.hasPath("messagePrefix") ? options.getString("messagePrefix") : "Message";
        this.maxMessages = options.hasPath("maxMessages") ? options.getLong("maxMessages") : -1L;
        this.throughputWindowSeconds = options.hasPath("throughputWindowSeconds") ? options.getInt("throughputWindowSeconds") : 5;
    }

    @Override
    protected void run() throws InterruptedException {
        this.outputQueue = getRequiredResource("output", IOutputQueueResource.class);
        long messageCounter = 0;
        while (!Thread.currentThread().isInterrupted() && (maxMessages == -1 || messageCounter < maxMessages)) {
            checkPause();

            DummyMessage message = DummyMessage.newBuilder()
                    .setId((int) messageCounter)
                    .setContent(messagePrefix + "-" + messageCounter)
                    .setTimestamp(System.currentTimeMillis())
                    .build();

            try {
                outputQueue.put(message);
                messagesSent.incrementAndGet();
                messageTimestamps.add(System.currentTimeMillis());
                logger.debug("Sent message: {}", message.getContent());
                messageCounter++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Producer interrupted while sending message.");
                break;
            } catch (Exception e) {
                OperationalError error = new OperationalError(Instant.now(), "SEND_ERROR", "Failed to send message", e.getMessage());
                errors.add(error);
                logger.warn("Failed to send message: {}", e.getMessage());
            }

            if (intervalMs > 0) {
                Thread.sleep(intervalMs);
            }
        }
        if (maxMessages != -1 && messageCounter >= maxMessages) {
            logger.info("Reached max message limit of {}. Stopping service.", maxMessages);
        }
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        metrics.put("messages_sent", messagesSent.get());
        metrics.put("throughput_per_sec", calculateThroughput());
        return metrics;
    }

    @Override
    public List<OperationalError> getErrors() {
        // Return an immutable copy to ensure thread safety
        return Collections.unmodifiableList(List.copyOf(errors));
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }

    @Override
    public boolean isHealthy() {
        return getCurrentState() != State.ERROR;
    }

    private double calculateThroughput() {
        long now = System.currentTimeMillis();
        long windowStart = now - (throughputWindowSeconds * 1000L);
        messageTimestamps.removeIf(timestamp -> timestamp < windowStart);
        if (throughputWindowSeconds == 0) return 0;
        return (double) messageTimestamps.size() / throughputWindowSeconds;
    }
}