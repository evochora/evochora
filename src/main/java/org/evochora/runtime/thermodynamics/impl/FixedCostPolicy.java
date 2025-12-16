package org.evochora.runtime.thermodynamics.impl;

import com.typesafe.config.Config;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;
import org.evochora.runtime.spi.thermodynamics.ThermodynamicContext;

/**
 * A simple policy that applies fixed energy costs and entropy deltas.
 * Useful for default behavior and simple instructions like NOP or basic arithmetic.
 * <p>
 * Configuration options:
 * <ul>
 *   <li>{@code energy}: The fixed energy cost (default: 1)</li>
 *   <li>{@code entropy}: The fixed entropy change (default: 1)</li>
 * </ul>
 */
public class FixedCostPolicy implements IThermodynamicPolicy {

    private int energyCost;
    private int entropyDelta;

    @Override
    public void initialize(Config options) {
        // Support both old and new names for backward compatibility
        this.energyCost = options.hasPath("energy") ? options.getInt("energy") 
            : (options.hasPath("energy-cost") ? options.getInt("energy-cost") : 1);
        this.entropyDelta = options.hasPath("entropy") ? options.getInt("entropy")
            : (options.hasPath("entropy-delta") ? options.getInt("entropy-delta") : 1);
    }

    @Override
    public int getEnergyCost(ThermodynamicContext context) {
        return this.energyCost;
    }

    @Override
    public int getEntropyDelta(ThermodynamicContext context) {
        return this.entropyDelta;
    }
}

