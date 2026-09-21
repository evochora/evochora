package org.evochora.runtime.thermodynamics.impl;

import com.typesafe.config.Config;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;

/**
 * A simple policy that applies fixed energy costs and entropy deltas.
 * Useful for default behavior and simple instructions like NOP or basic arithmetic.
 * <p>
 * It prices nothing beyond its base values: an instruction under this policy costs the same
 * whether it touched a cell or not.
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
        this.energyCost = options.hasPath("energy") ? options.getInt("energy") : 1;
        this.entropyDelta = options.hasPath("entropy") ? options.getInt("entropy") : 1;
    }

    @Override
    public int baseEnergy() {
        return this.energyCost;
    }

    @Override
    public int baseEntropy() {
        return this.entropyDelta;
    }

    @Override
    public Thermodynamics priceEffect(boolean isWrite, int moleculeInt, int ownerId, int actorId) {
        return FREE;
    }
}
