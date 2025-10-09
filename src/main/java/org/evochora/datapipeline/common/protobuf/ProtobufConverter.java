/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.common.protobuf;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

/**
 * A utility class for converting Protocol Buffers (Protobuf) messages to and from JSON.
 * <p>
 * This class uses {@link JsonFormat} to handle the conversion, ensuring that the
 * output is compatible with the standard JSON mapping for Protobuf.
 */
public final class ProtobufConverter {

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
}