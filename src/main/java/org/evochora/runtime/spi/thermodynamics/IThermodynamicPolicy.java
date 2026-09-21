package org.evochora.runtime.spi.thermodynamics;

import com.typesafe.config.Config;

/**
 * Service Provider Interface for defining thermodynamic policies.
 * <p>
 * A policy prices what an instruction did, not what it might do:
 * <pre>
 * energy  = baseEnergy()  + sum over effects of priceEffect(...).energyCost()
 * entropy = baseEntropy() + sum over effects of priceEffect(...).entropyDelta()
 * </pre>
 * The base values do not depend on what the instruction does and are charged before it runs, so
 * an instruction that reads its own energy or entropy sees its own base cost already paid. The
 * effects are collected while it runs and priced afterwards. An instruction that failed, or that
 * lost its write conflict, recorded no effect and therefore pays its base values alone. A policy
 * is never told which instruction it is pricing and cannot reach the environment: everything it
 * needs about an effect is passed as a plain value.
 * <p>
 * The effects are summed before they are booked, so a policy may return values of either sign for
 * either quantity without the order of the effects influencing the result - which is what keeps
 * the outcome reproducible when an organism's registers sit against a clamp. The base values are
 * booked separately and therefore before the effects, which matters at either clamp: a negative
 * {@link #baseEntropy()} is lost against the entropy floor, and a negative {@link #baseEnergy()}
 * against the energy ceiling, where the effects, booked together with them, would have carried
 * them. Every shipped configuration charges positive base values, for which the split makes no
 * difference.
 */
public interface IThermodynamicPolicy {

    /**
     * The price of a base value or of one effect.
     *
     * @param energyCost The energy cost (positive = consumption, negative = gain)
     * @param entropyDelta The entropy delta (positive = generation, negative = dissipation)
     */
    record Thermodynamics(int energyCost, int entropyDelta) {}

    /** The price of an effect a policy has no rule for. */
    Thermodynamics FREE = new Thermodynamics(0, 0);

    /**
     * Initializes the policy with its specific configuration object.
     * This method is called by the {@code ThermodynamicPolicyManager} immediately
     * after the policy is instantiated.
     *
     * @param options The HOCON configuration object for this policy instance.
     *                This can be an empty Config if no options are provided in the
     *                main configuration file.
     */
    void initialize(Config options);

    /**
     * The energy every execution of the instruction costs, whatever it did.
     *
     * @return The base energy cost; positive consumes, negative gains.
     */
    int baseEnergy();

    /**
     * The entropy every execution of the instruction contributes, whatever it did.
     *
     * @return The base entropy delta; positive generates, negative dissipates.
     */
    int baseEntropy();

    /**
     * Prices one effect an instruction had on a cell.
     *
     * @param isWrite {@code true} when the instruction stored a molecule in the cell,
     *                {@code false} when it consumed the molecule that stood there.
     * @param moleculeInt The molecule the effect concerns, in the packed form the environment
     *                    stores: for a read what was taken out, for a write what was put in.
     * @param ownerId The cell's owner before the effect; 0 for an unowned cell.
     * @param actorId The id of the organism the instruction belongs to.
     * @return The price of this effect, or {@link #FREE} when no rule applies to it.
     */
    Thermodynamics priceEffect(boolean isWrite, int moleculeInt, int ownerId, int actorId);
}
