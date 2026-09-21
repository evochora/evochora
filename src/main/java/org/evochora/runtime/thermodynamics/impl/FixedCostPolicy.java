package org.evochora.runtime.thermodynamics.impl;

import java.util.Set;

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

    /** Keys this policy accepts under its {@code options} block. */
    private static final Set<String> OPTION_KEYS = Set.of("energy", "entropy");

    private int energyCost;
    private int entropyDelta;

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the options carry a key this policy does not understand,
     *         which would otherwise be dropped in silence and leave the instruction priced by
     *         the defaults.
     */
    @Override
    public void initialize(Config options) {
        for (String key : options.root().keySet()) {
            if (!OPTION_KEYS.contains(key)) {
                throw new IllegalStateException("Unknown key '" + key
                        + "' in options of FixedCostPolicy. Accepted keys: energy, entropy");
            }
        }
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
