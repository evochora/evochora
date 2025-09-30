package org.evochora.datapipeline.api.contracts;

import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
public class ProtobufIntegrationTest {

    @Test
    void testSerializationDeserialization() throws InvalidProtocolBufferException {
        long timestamp = System.currentTimeMillis();
        SystemContracts.DummyMessage originalMessage = SystemContracts.DummyMessage.newBuilder()
                .setId(123)
                .setContent("Test content")
                .setTimestamp(timestamp)
                .build();

        byte[] serializedMessage = originalMessage.toByteArray();

        SystemContracts.DummyMessage deserializedMessage = SystemContracts.DummyMessage.parseFrom(serializedMessage);

        assertEquals(originalMessage.getId(), deserializedMessage.getId());
        assertEquals(originalMessage.getContent(), deserializedMessage.getContent());
        assertEquals(originalMessage.getTimestamp(), deserializedMessage.getTimestamp());
    }
}