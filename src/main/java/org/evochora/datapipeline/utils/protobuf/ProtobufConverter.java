package org.evochora.datapipeline.utils.protobuf;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A utility class for converting Protocol Buffers (Protobuf) messages to and from JSON.
 * <p>
 * This class uses {@link JsonFormat} to handle the conversion, ensuring that the
 * output is compatible with the standard JSON mapping for Protobuf.
 * <p>
 * Performance: {@link #fromJson(String, Class)} caches reflection lookups per message class
 * for O(1) access after first call.
 */
public final class ProtobufConverter {
    
    // Cache for newBuilder() methods to avoid repeated reflection lookups
    private static final ConcurrentHashMap<Class<?>, Method> builderMethodCache = new ConcurrentHashMap<>();

    private ProtobufConverter() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Converts a Protobuf message to its JSON representation.
     *
     * @param message The Protobuf message to convert.
     * @return A JSON string representing the message.
     * @throws RuntimeException if the conversion fails.
     */
    public static String toJson(Message message) {
        try {
            return JsonFormat.printer().print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to convert protobuf message to JSON.", e);
        }
    }

    /**
     * Converts a JSON string to a Protobuf message.
     * <p>
     * Performance: Caches the newBuilder() method lookup per message class.
     * First call: O(n) reflection lookup. Subsequent calls: O(1) cache hit.
     *
     * @param json The JSON string to convert
     * @param messageClass The class of the Protobuf message
     * @param <T> The type of the Protobuf message
     * @return The parsed Protobuf message
     * @throws RuntimeException if the conversion fails
     */
    public static <T extends Message> T fromJson(String json, Class<T> messageClass) {
        try {
            // Get cached newBuilder() method (or compute and cache on first access)
            Method method = builderMethodCache.computeIfAbsent(messageClass, clazz -> {
                try {
                    return clazz.getMethod("newBuilder");
                } catch (NoSuchMethodException e) {
                    throw new RuntimeException("Protobuf message class must have newBuilder() method: " + clazz.getName(), e);
                }
            });
            
            // Invoke cached method to get builder
            Message.Builder builder = (Message.Builder) method.invoke(null);
            
            // Parse JSON into builder
            JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
            
            @SuppressWarnings("unchecked")
            T result = (T) builder.build();
            return result;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert JSON to protobuf message: " + messageClass.getName(), e);
        }
    }
}