package org.evochora.datapipeline.services;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.contracts.SystemContracts.DummyMessage;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * A dummy producer service that sends Protobuf messages to an output queue.
 * It serves as a test service and a reference implementation.
 *
 * <h3>Configuration Options:</h3>
 * <ul>
 *   <li><b>intervalMs</b>: Milliseconds between messages (default: 1000).</li>
 *   <li><b>messagePrefix</b>: Prefix for the message content (default: "Message").</li>
 *   <li><b>maxMessages</b>: Maximum messages to send, -1 for unlimited (default: -1).</li>
 *   <li><b>metricsWindowSeconds</b>: Time window in seconds for throughput calculation (default: 5).</li>
 * </ul>
 */
public class DummyProducerService extends AbstractService {

    private static final Logger logger = LoggerFactory.getLogger(DummyProducerService.class);

    private final IOutputQueueResource<DummyMessage> outputQueue;
    private final long intervalMs;
    private final String messagePrefix;
    private final long maxMessages;
    private final int metricsWindowSeconds;

    private final AtomicLong messagesSent = new AtomicLong(0);
    private final SlidingWindowCounter throughputCounter;

    public DummyProducerService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.intervalMs = options.hasPath("intervalMs") ? options.getLong("intervalMs") : 1000L;
        this.messagePrefix = options.hasPath("messagePrefix") ? options.getString("messagePrefix") : "Message";
        this.maxMessages = options.hasPath("maxMessages") ? options.getLong("maxMessages") : -1L;
        this.metricsWindowSeconds = options.hasPath("metricsWindowSeconds") ? options.getInt("metricsWindowSeconds") : 5;
        this.throughputCounter = new SlidingWindowCounter(metricsWindowSeconds);

        @SuppressWarnings("unchecked")
        IOutputQueueResource<DummyMessage> queue = (IOutputQueueResource<DummyMessage>) getRequiredResource("output", IOutputQueueResource.class);
        this.outputQueue = queue;
    }

    @Override
    protected void run() throws InterruptedException {
        long messageCounter = 0;
        // Check both isStopRequested() (graceful) and isInterrupted() (forced)
        while (!isStopRequested() && !Thread.currentThread().isInterrupted() && (maxMessages == -1 || messageCounter < maxMessages)) {
            checkPause();

            DummyMessage message = DummyMessage.newBuilder()
                    .setId((int) messageCounter)
                    .setContent(messagePrefix + "-" + messageCounter)
                    .setTimestamp(System.currentTimeMillis())
                    .build();

            try {
                outputQueue.put(message);
                messagesSent.incrementAndGet();
                throughputCounter.recordCount();
                logger.debug("Sent message: {}", message.getContent());
                messageCounter++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Producer interrupted while sending message");
                break;
            } catch (Exception e) {
                logger.warn("Failed to send message");
                recordError("SEND_ERROR", "Failed to send message", String.format("Message counter: %d", messageCounter));
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
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("messages_sent", messagesSent.get());
        metrics.put("throughput_per_sec", throughputCounter.getRate());
    }
}