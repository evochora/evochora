package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.contracts.RawTickData;
import org.evochora.datapipeline.core.OutputChannelBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A placeholder implementation of the DummyService service.
 * <p>
 * This service simulates the core simulation engine that would run the actual
 * Evochora simulation and publish RawTickData messages to the pipeline.
 * For now, it's a simple placeholder that demonstrates the service lifecycle.
 * </p>
 */
public class DummyService extends AbstractService {

    private static final Logger log = LoggerFactory.getLogger(DummyService.class);

    private int tickCount = 0;
    private final int maxTicks;

    public DummyService(Config options) {
        this.maxTicks = options.hasPath("maxTicks") ? options.getInt("maxTicks") : 1000;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addOutputChannel(String portName, OutputChannelBinding<?> binding) {
        registerOutputChannel(portName, binding);
    }

    @Override
    protected void run() {
        IOutputChannel<RawTickData> outputChannel = getRequiredOutputChannel("tickData"); // Assuming port name is "tickData"
        log.debug("Placeholder DummyService is running.");
        
        try {
            while (currentState.get() == org.evochora.datapipeline.api.services.State.RUNNING 
                   && tickCount < maxTicks 
                   && !Thread.currentThread().isInterrupted()) {
                
                synchronized (pauseLock) {
                    while (paused) {
                        pauseLock.wait();
                    }
                }
                
                if (currentState.get() == org.evochora.datapipeline.api.services.State.STOPPED) {
                    break;
                }
                
                // Simulate tick processing
                simulateTick(outputChannel);
                
                // Small delay to prevent overwhelming the system
                Thread.sleep(100);
            }
            
            log.debug("DummyService completed after {} ticks", tickCount);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("DummyService interrupted");
        } finally {
            currentState.set(org.evochora.datapipeline.api.services.State.STOPPED);
        }
    }

    private void simulateTick(IOutputChannel<RawTickData> outputChannel) {
        tickCount++;
        
        // Create a placeholder RawTickData message
        RawTickData tickData = new RawTickData();
        tickData.setSimulationRunId("sim-run-" + System.currentTimeMillis());
        tickData.setTickNumber(tickCount);
        // Note: In a real implementation, this would contain actual cell and organism data
        
        try {
            if (outputChannel != null) {
                outputChannel.write(tickData);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while writing tick data", e);
        }
        
        // Log progress every 100 ticks
        if (tickCount % 100 == 0) {
            log.debug("Processed tick: {}", tickCount);
        }
    }
}
