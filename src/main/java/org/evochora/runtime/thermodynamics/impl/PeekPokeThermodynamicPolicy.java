package org.evochora.runtime.thermodynamics.impl;

import com.typesafe.config.Config;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;
import org.evochora.runtime.spi.thermodynamics.ThermodynamicContext;

/**
 * A combined policy for PEEK+POKE instructions (PPK).
 * Delegates to {@link PeekThermodynamicPolicy} and {@link PokeThermodynamicPolicy}
 * to calculate the combined costs and entropy effects.
 * <p>
 * Configuration options:
 * <ul>
 *   <li>{@code peek-rules}: Configuration block for the underlying Peek policy.</li>
 *   <li>{@code poke-rules}: Configuration block for the underlying Poke policy.</li>
 * </ul>
 */
public class PeekPokeThermodynamicPolicy implements IThermodynamicPolicy {

    private final PeekThermodynamicPolicy peekPolicy = new PeekThermodynamicPolicy();
    private final PokeThermodynamicPolicy pokePolicy = new PokeThermodynamicPolicy();

    @Override
    public void initialize(Config options) {
        if (options.hasPath("peek-rules")) {
            peekPolicy.initialize(options.getConfig("peek-rules"));
        }
        if (options.hasPath("poke-rules")) {
            pokePolicy.initialize(options.getConfig("poke-rules"));
        }
    }

    @Override
    public int getEnergyCost(ThermodynamicContext context) {
        // Combined cost: Cost of PEEK + Cost of POKE
        // Both policies no longer have base costs, so we simply sum them.
        
        int peekCost = peekPolicy.getEnergyCost(context);
        int pokeCost = pokePolicy.getEnergyCost(context);
        
        return peekCost + pokeCost;
    }

    @Override
    public int getEntropyDelta(ThermodynamicContext context) {
        // PPK entropy delta: PEEK generates entropy (positive), POKE dissipates entropy (negative)
        // Simply sum both deltas to get the net entropy change
        int peekDelta = peekPolicy.getEntropyDelta(context);
        int pokeDelta = pokePolicy.getEntropyDelta(context);
        return peekDelta + pokeDelta;
    }
}

