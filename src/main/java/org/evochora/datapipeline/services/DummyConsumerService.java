package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.PipelineContracts.DummyMessage;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.wrappers.queues.IInputQueueResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A dummy consumer service that receives Protobuf messages from an input queue.
 * It serves as a test service and a reference implementation.
 *
 * <h3>Configuration Options:</h3>
 * <ul>
 *   <li><b>processingDelayMs</b>: Artificial delay per message in milliseconds (default: 0).</li>
 *   <li><b>logReceivedMessages</b>: Whether to log received messages at DEBUG level (default: false).</li>
 *   <li><b>maxMessages</b>: Maximum messages to process, -1 for unlimited (default: -1).</li>
 *   <li><b>throughputWindowSeconds</b>: Time window in seconds for throughput calculation (default: 5).</li>
 * </ul>
 */
public class DummyConsumerService extends AbstractService implements IMonitorable {

    private static final Logger logger = LoggerFactory.getLogger(DummyConsumerService.class);

    private final IInputQueueResource<DummyMessage> inputQueue;
    private final long processingDelayMs;
    private final boolean logReceivedMessages;
    private final long maxMessages;
    private final int throughputWindowSeconds;

    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Long> messageTimestamps = new ConcurrentLinkedDeque<>();

    @SuppressWarnings("unchecked")
    public DummyConsumerService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.processingDelayMs = options.hasPath("processingDelayMs") ? options.getLong("processingDelayMs") : 0L;
        this.logReceivedMessages = options.hasPath("logReceivedMessages") && options.getBoolean("logReceivedMessages");
        this.maxMessages = options.hasPath("maxMessages") ? options.getLong("maxMessages") : -1L;
        this.throughputWindowSeconds = options.hasPath("throughputWindowSeconds") ? options.getInt("throughputWindowSeconds") : 5;
        this.inputQueue = getRequiredResource("input", IInputQueueResource.class);
    }

    @Override
    protected void run() throws InterruptedException {
        long messageCounter = 0;
        while (!Thread.currentThread().isInterrupted() && (maxMessages == -1 || messageCounter < maxMessages)) {
            checkPause();

            try {
                DummyMessage message = inputQueue.take();
                if (message != null) {
                    messagesReceived.incrementAndGet();
                    messageTimestamps.add(System.currentTimeMillis());
                    if (logReceivedMessages) {
                        logger.debug("Received message: {}", message.getContent());
                    }
                    if (processingDelayMs > 0) {
                        Thread.sleep(processingDelayMs);
                    }
                    messageCounter++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Consumer service interrupted while waiting for message.");
                break;
            } catch (Exception e) {
                OperationalError error = new OperationalError(Instant.now(), "RECEIVE_ERROR", "Failed to receive message", e.getMessage());
                errors.add(error);
                logger.warn("Failed to receive message: {}", e.getMessage());
            }
        }
        if (maxMessages != -1 && messageCounter >= maxMessages) {
            logger.info("Reached max message limit of {}. Stopping service.", maxMessages);
        }
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        metrics.put("messages_received", messagesReceived.get());
        metrics.put("throughput_per_sec", calculateThroughput());
        return metrics;
    }

    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
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