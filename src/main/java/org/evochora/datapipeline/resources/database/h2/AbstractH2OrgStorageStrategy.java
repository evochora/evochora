package org.evochora.datapipeline.resources.database.h2;

import java.util.Objects;

import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Abstract base class for H2 organism storage strategies.
 * <p>
 * Enforces constructor contract: All strategies MUST accept Config parameter.
 * <p>
 * Provides common infrastructure:
 * <ul>
 *   <li>Config options access (protected final)</li>
 *   <li>Logger instance (protected final)</li>
 *   <li>Compression codec (protected final)</li>
 * </ul>
 * <p>
 * <strong>Rationale:</strong> Ensures all strategies can be instantiated via reflection
 * with consistent constructor signature. The compiler enforces that subclasses call
 * super(options), preventing runtime errors from missing constructors.
 */
public abstract class AbstractH2OrgStorageStrategy implements IH2OrgStorageStrategy {
    
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final Config options;
    protected final ICompressionCodec codec;
    
    /**
     * Creates storage strategy with configuration.
     * <p>
     * <strong>Subclass Requirement:</strong> All subclasses MUST call super(options).
     * The compiler enforces this.
     * 
     * @param options Strategy configuration (may be empty, never null)
     */
    protected AbstractH2OrgStorageStrategy(Config options) {
        this.options = Objects.requireNonNull(options, "options cannot be null");
        this.codec = CompressionCodecFactory.create(options);
        log.debug("{} initialized with compression: {}", getClass().getSimpleName(), codec.getName());
    }
    
    /**
     * Returns the configured compression codec.
     * <p>
     * Subclasses use this for BLOB compression/decompression.
     *
     * @return The compression codec (never null)
     */
    protected ICompressionCodec getCodec() {
        return codec;
    }
}
