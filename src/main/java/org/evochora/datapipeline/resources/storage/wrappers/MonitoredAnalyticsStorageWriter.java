package org.evochora.datapipeline.resources.storage.wrappers;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageWrite;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.storage.AbstractBatchStorageResource;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper for IAnalyticsStorageWrite that adds monitoring (bytes written, latency).
 */
public class MonitoredAnalyticsStorageWriter extends AbstractResource implements IAnalyticsStorageWrite, IWrappedResource {

    private static final Logger log = LoggerFactory.getLogger(MonitoredAnalyticsStorageWriter.class);

    private final IAnalyticsStorageWrite delegate;
    private final AbstractBatchStorageResource resource;
    private final ResourceContext context;
    
    // Metrics
    private final AtomicLong filesWritten = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    
    private final SlidingWindowCounter writeThroughput;
    private final SlidingWindowPercentiles writeLatency;

    public MonitoredAnalyticsStorageWriter(AbstractBatchStorageResource resource, ResourceContext context) {
        super(resource.getResourceName() + "-" + context.usageType(), resource.getOptions());
        this.resource = resource;
        this.delegate = (IAnalyticsStorageWrite) resource;
        this.context = context;
        
        int windowSeconds = resource.getOptions().hasPath("metricsWindowSeconds") 
            ? resource.getOptions().getInt("metricsWindowSeconds") : 60;
            
        this.writeThroughput = new SlidingWindowCounter(windowSeconds);
        this.writeLatency = new SlidingWindowPercentiles(windowSeconds);
    }

    @Override
    public OutputStream openAnalyticsOutputStream(String runId, String metricId, String lodLevel, String subPath, String filename) throws IOException {
        long start = System.nanoTime();
        try {
            OutputStream raw = delegate.openAnalyticsOutputStream(runId, metricId, lodLevel, subPath, filename);
            
            // Return a wrapper stream to count bytes on close
            return new OutputStream() {
                private long bytes = 0;
                
                @Override
                public void write(int b) throws IOException {
                    raw.write(b);
                    bytes++;
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    raw.write(b, off, len);
                    bytes += len;
                }

                @Override
                public void close() throws IOException {
                    try {
                        raw.close();
                        recordSuccess(bytes, System.nanoTime() - start);
                    } catch (IOException e) {
                        log.warn("Failed to close analytics output stream for runId={}, metricId={}, file={}/{}/{}",
                            runId, metricId, lodLevel, subPath, filename);
                        recordFailure();
                        throw e;
                    }
                }
            };
        } catch (IOException e) {
            log.warn("Failed to open analytics output stream for runId={}, metricId={}, file={}/{}/{}",
                runId, metricId, lodLevel, subPath, filename);
            recordFailure();
            throw e;
        }
    }
    
    private void recordSuccess(long bytes, long latencyNanos) {
        filesWritten.incrementAndGet();
        bytesWritten.addAndGet(bytes);
        writeThroughput.recordSum(bytes);
        writeLatency.record(latencyNanos);
    }
    
    private void recordFailure() {
        writeErrors.incrementAndGet();
    }

    @Override
    public UsageState getUsageState(String usageType) {
        // For writer wrappers, we only care about our specific usage type context
        if (context.usageType().equals(usageType)) {
            return isHealthy() ? UsageState.ACTIVE : UsageState.FAILED;
        }
        // Fallback to checking delegate if usage type differs (should rarely happen for wrappers)
        return resource.getUsageState(usageType);
    }
    
    // Removed getContext() as it is not part of IWrappedResource interface
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        metrics.put("files_written", filesWritten.get());
        metrics.put("bytes_written", bytesWritten.get());
        metrics.put("write_errors", writeErrors.get());
        metrics.put("throughput_bytes_per_sec", writeThroughput.getRate());
        metrics.put("latency_p50_ms", writeLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("latency_p99_ms", writeLatency.getPercentile(99) / 1_000_000.0);
    }
}

