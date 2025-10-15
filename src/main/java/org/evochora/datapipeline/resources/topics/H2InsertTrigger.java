package org.evochora.datapipeline.resources.topics;

import org.h2.api.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2 database trigger for instant message notifications.
 * <p>
 * This trigger is invoked by H2 after each INSERT into the topic_messages table.
 * It pushes the new message ID into a notification queue, instantly waking up
 * waiting readers for event-driven message delivery.
 * <p>
 * <strong>Event-Driven Architecture:</strong>
 * <pre>
 * Writer → SQL INSERT → H2 Trigger → BlockingQueue.offer() → Reader wake-up (INSTANT!)
 * </pre>
 * <p>
 * <strong>Centralized Table Design:</strong>
 * With a single {@code topic_messages} table shared by all topics, this trigger uses the
 * {@code topic_name} column (index 1 in newRow) to route notifications to the correct queue.
 * <p>
 * <strong>Static Registry:</strong>
 * Uses a static {@link ConcurrentHashMap} to map topic names to notification queues.
 * This allows multiple {@link H2TopicResource} instances (different topics) to use
 * the same trigger and centralized table with their own notification queues.
 * <p>
 * <strong>Run Isolation:</strong>
 * The registry key is schema-qualified ({@code "topicName:schemaName"}) to prevent
 * notification leakage between different simulation runs that use different schemas.
 * <p>
 * <strong>Lifecycle:</strong>
 * <ul>
 *   <li>{@link H2TopicResource} registers its queue via {@link #registerNotificationQueue(String, String, BlockingQueue)}</li>
 *   <li>H2 calls {@link #fire(Connection, Object[], Object[])} on each INSERT</li>
 *   <li>{@link H2TopicResource} deregisters on close via {@link #deregisterNotificationQueue(String, String)}</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong>
 * This class is thread-safe. The static registry uses {@link ConcurrentHashMap}.
 *
 * @see org.h2.api.Trigger
 */
public class H2InsertTrigger implements Trigger {
    
    private static final Logger log = LoggerFactory.getLogger(H2InsertTrigger.class);
    
    // Static registry: "topicName:schemaName" → notification queue
    // Schema-qualified key ensures run isolation (different runs = different schemas)
    private static final Map<String, BlockingQueue<Long>> notificationQueues = new ConcurrentHashMap<>();
    
    /**
     * Registers a notification queue for a specific topic in a specific schema.
     * <p>
     * Called by {@link H2TopicResource} during initialization.
     * <p>
     * <strong>Run Isolation:</strong>
     * The key includes both topic name AND schema name to ensure that different
     * simulation runs (which use different schemas) have separate notification queues.
     *
     * @param topicName The topic name (resource name).
     * @param schemaName The H2 schema name (e.g., "SIM_20251006_UUID").
     * @param queue The notification queue.
     */
    public static void registerNotificationQueue(String topicName, String schemaName, BlockingQueue<Long> queue) {
        String key = topicName + ":" + schemaName;
        notificationQueues.put(key, queue);
        log.debug("Registered notification queue for topic '{}' in schema '{}'", topicName, schemaName);
    }
    
    /**
     * Deregisters a notification queue for a specific topic in a specific schema.
     * <p>
     * Called by {@link H2TopicResource} during shutdown.
     *
     * @param topicName The topic name (resource name).
     * @param schemaName The H2 schema name (e.g., "SIM_20251006_UUID").
     */
    public static void deregisterNotificationQueue(String topicName, String schemaName) {
        String key = topicName + ":" + schemaName;
        notificationQueues.remove(key);
        log.debug("Deregistered notification queue for topic '{}' in schema '{}'", topicName, schemaName);
    }
    
    @Override
    public void init(Connection conn, String schemaName, String triggerName,
                     String tableName, boolean before, int type) {
        // With centralized table, we don't cache the queue here
        // Instead, we look it up per-message based on topic_name column
        log.debug("H2 trigger '{}' initialized for centralized table '{}'", triggerName, tableName);
    }
    
    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (newRow != null && newRow.length > 1) {
            // Shared table schema: id, topic_name, message_id, timestamp, envelope, ...
            Long messageId = (Long) newRow[0];      // Column 0: id (BIGINT AUTO_INCREMENT)
            String topicName = (String) newRow[1];  // Column 1: topic_name (VARCHAR)
            
            // Get current schema for run isolation
            String schemaName = conn.getSchema();
            String key = topicName + ":" + schemaName;
            
            // Look up the notification queue for this specific topic in this specific schema
            BlockingQueue<Long> queue = notificationQueues.get(key);
            
            if (queue != null) {
                boolean offered = queue.offer(messageId);
                
                if (!offered) {
                    // Queue full - should not happen with unbounded LinkedBlockingQueue
                    log.warn("Notification queue full for topic '{}' in schema '{}', message ID {} not notified", 
                        topicName, schemaName, messageId);
                }
            } else {
                // No queue registered for this topic - may happen during shutdown
                log.debug("No notification queue registered for topic '{}' in schema '{}', message ID {} not notified", 
                    topicName, schemaName, messageId);
            }
        }
    }
    
    @Override
    public void close() {
        // No resources to close
    }
    
    @Override
    public void remove() {
        // Called when trigger is dropped - no cleanup needed
    }
}

