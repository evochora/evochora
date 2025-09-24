package org.evochora.datapipeline.core;

import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.Direction;
import org.evochora.datapipeline.channels.InMemoryChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
public class ChannelBindingTest {

    private InMemoryChannel<String> testChannel;
    private InputChannelBinding<String> inputBinding;
    private OutputChannelBinding<String> outputBinding;

    @BeforeEach
    void setUp() {
        testChannel = new InMemoryChannel<>(com.typesafe.config.ConfigFactory.empty());
        
        inputBinding = new InputChannelBinding<>("test-service", "test-channel", "test-channel", testChannel);
        outputBinding = new OutputChannelBinding<>("test-service", "test-channel", "test-channel", testChannel);
    }

    @Test
    void testInputChannelBindingBasicProperties() {
        assertEquals("test-service", inputBinding.getServiceName());
        assertEquals("test-channel", inputBinding.getPortName());
        assertEquals("test-channel", inputBinding.getChannelName());
        assertEquals(Direction.INPUT, inputBinding.getDirection());
        assertEquals(testChannel, inputBinding.getUnderlyingChannel());
        assertEquals(0, inputBinding.getAndResetCount());
    }

    @Test
    void testOutputChannelBindingBasicProperties() {
        assertEquals("test-service", outputBinding.getServiceName());
        assertEquals("test-channel", outputBinding.getPortName());
        assertEquals("test-channel", outputBinding.getChannelName());
        assertEquals(Direction.OUTPUT, outputBinding.getDirection());
        assertEquals(testChannel, outputBinding.getUnderlyingChannel());
        assertEquals(0, outputBinding.getAndResetCount());
    }

    @Test
    void testInputChannelBindingReadAndCount() throws InterruptedException {
        // Write a message to the underlying channel
        testChannel.write("test-message");
        
        // Read through the binding
        String message = inputBinding.read();
        assertEquals("test-message", message);
        
        // Verify count was incremented
        assertEquals(1, inputBinding.getAndResetCount());
        assertEquals(0, inputBinding.getAndResetCount()); // Should reset after get
    }

    @Test
    void testOutputChannelBindingWriteAndCount() throws InterruptedException {
        // Write through the binding
        outputBinding.write("test-message");
        
        // Verify count was incremented
        assertEquals(1, outputBinding.getAndResetCount());
        assertEquals(0, outputBinding.getAndResetCount()); // Should reset after get
        
        // Verify message was written to underlying channel
        String message = testChannel.read();
        assertEquals("test-message", message);
    }

    @Test
    void testMultipleOperationsCountAccumulation() throws InterruptedException {
        // Multiple writes through output binding
        outputBinding.write("message1");
        outputBinding.write("message2");
        outputBinding.write("message3");
        
        assertEquals(3, outputBinding.getAndResetCount());
        
        // Multiple reads through input binding
        String msg1 = inputBinding.read();
        String msg2 = inputBinding.read();
        String msg3 = inputBinding.read();
        
        assertEquals("message1", msg1);
        assertEquals("message2", msg2);
        assertEquals("message3", msg3);
        assertEquals(3, inputBinding.getAndResetCount());
    }

    @Test
    void testChannelBindingToString() {
        String inputString = inputBinding.toString();
        assertTrue(inputString.contains("InputChannelBinding"));
        assertTrue(inputString.contains("service=test-service"));
        assertTrue(inputString.contains("port=test-channel"));
        assertTrue(inputString.contains("channel=test-channel"));
        assertTrue(inputString.contains("direction=INPUT"));
        
        String outputString = outputBinding.toString();
        assertTrue(outputString.contains("OutputChannelBinding"));
        assertTrue(outputString.contains("service=test-service"));
        assertTrue(outputString.contains("port=test-channel"));
        assertTrue(outputString.contains("channel=test-channel"));
        assertTrue(outputString.contains("direction=OUTPUT"));
    }

    @Test
    void testChannelBindingImplementsInterfaces() {
        assertTrue(inputBinding instanceof IInputChannel);
        assertTrue(outputBinding instanceof IOutputChannel);
    }

    @Test
    void testGetDelegateAccess() {
        assertEquals(testChannel, inputBinding.getDelegate());
        assertEquals(testChannel, outputBinding.getDelegate());
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Test that multiple threads can safely access the binding
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    outputBinding.write("message" + i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    inputBinding.read();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        writer.start();
        reader.start();
        
        writer.join();
        reader.join();
        
        // Verify counts
        assertEquals(100, outputBinding.getAndResetCount());
        assertEquals(100, inputBinding.getAndResetCount());
    }

    @Test
    void testErrorCountTracking() {
        // Test that error count starts at zero
        assertEquals(0, inputBinding.getErrorCount());
        assertEquals(0, outputBinding.getErrorCount());
        
        // Test error count increment
        inputBinding.incrementErrorCountForTesting();
        inputBinding.incrementErrorCountForTesting();
        assertEquals(2, inputBinding.getErrorCount());
        
        outputBinding.incrementErrorCountForTesting();
        assertEquals(1, outputBinding.getErrorCount());
        
        // Test error count reset
        inputBinding.resetErrorCount();
        outputBinding.resetErrorCount();
        assertEquals(0, inputBinding.getErrorCount());
        assertEquals(0, outputBinding.getErrorCount());
    }

    @Test
    void testErrorCountAccumulation() {
        // Test that error count accumulates correctly
        for (int i = 0; i < 5; i++) {
            inputBinding.incrementErrorCountForTesting();
        }
        assertEquals(5, inputBinding.getErrorCount());
        
        // Reset and verify
        inputBinding.resetErrorCount();
        assertEquals(0, inputBinding.getErrorCount());
        
        // Accumulate again
        for (int i = 0; i < 3; i++) {
            inputBinding.incrementErrorCountForTesting();
        }
        assertEquals(3, inputBinding.getErrorCount());
    }
}
