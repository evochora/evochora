package org.evochora.datapipeline.resources.database.h2;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for H2 environment storage strategies.
 * <p>
 * Enforces constructor contract: All strategies MUST accept Config parameter.
 * <p>
 * Provides common infrastructure:
 * <ul>
 *   <li>Config options access (protected final)</li>
 *   <li>Logger instance (protected final)</li>
 * </ul>
 * <p>
 * <strong>Rationale:</strong> Ensures all strategies can be instantiated via reflection
 * with consistent constructor signature. The compiler enforces that subclasses call
 * super(options), preventing runtime errors from missing constructors.
 */
public abstract class AbstractH2EnvStorageStrategy implements IH2EnvStorageStrategy {
    
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final Config options;
    
    /**
     * Creates storage strategy with configuration.
     * <p>
     * <strong>Subclass Requirement:</strong> All subclasses MUST call super(options).
     * The compiler enforces this.
     * 
     * @param options Strategy configuration (may be empty, never null)
     */
    protected AbstractH2EnvStorageStrategy(Config options) {
        this.options = java.util.Objects.requireNonNull(options, "options cannot be null");
    }
    
    // createSchema() and writeTicks() remain abstract - too strategy-specific
}


