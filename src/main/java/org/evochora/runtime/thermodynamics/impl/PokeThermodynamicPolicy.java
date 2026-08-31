package org.evochora.runtime.thermodynamics.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.evochora.runtime.isa.Instruction.ConflictResolutionStatus;
import org.evochora.runtime.isa.Instruction.Operand;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;
import org.evochora.runtime.spi.thermodynamics.ThermodynamicContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * A policy for POKE-like instructions that write to the environment.
 * Handles costs based on the written molecule type and entropy dissipation.
 * <p>
 * Important: POKE dissipates entropy (negative values), it does NOT generate entropy
 * from energy consumption. The energy cost is separate from entropy dissipation.
 * <p>
 * Configuration structure:
 * <pre>
 * ENERGY {
 *   energy-permille = 1000
 *   entropy-permille = -1000  # Negative = dissipation
 * },
 * CODE {
 *   energy = 5
 *   entropy-permille = -1000  # Negative = dissipation
 * }
 * </pre>
 * <p>
 * Entropy must be explicitly configured for each molecule type.
 */
public class PokeThermodynamicPolicy implements IThermodynamicPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(PokeThermodynamicPolicy.class);
    
    // Placeholder key for the default rule
    private static final int DEFAULT_TYPE_KEY = -1;

    private static class Rule {
        final int energyFixed;
        final int energyPerMille;
        final Integer entropyFixed;  // null if not configured (must be configured)
        final Integer entropyPerMille;  // null if not configured (must be configured)

        Rule(Config config) {
            // Both fixed and permille can be specified simultaneously - they will be added
            this.energyFixed = config.hasPath("energy") ? config.getInt("energy") : 0;
            this.energyPerMille = config.hasPath("energy-permille") ? config.getInt("energy-permille") : 0;
            
            // Entropy: at least one must be configured, but both can be present (they will be added)
            if (config.hasPath("entropy")) {
                this.entropyFixed = config.getInt("entropy");
            } else {
                this.entropyFixed = null;
            }
            
            if (config.hasPath("entropy-permille")) {
                this.entropyPerMille = config.getInt("entropy-permille");
            } else {
                this.entropyPerMille = null;
            }
            
            if (entropyFixed == null && entropyPerMille == null) {
                // Entropy must be explicitly configured (at least one)
                throw new IllegalStateException("Entropy not configured for PokeThermodynamicPolicy rule. Must specify either 'entropy' or 'entropy-permille' (or both) in evochora.conf.");
            }
        }

        int calculateEnergy(Molecule molecule) {
            int fixed = energyFixed;
            int permille = 0;
            if (energyPerMille != 0) {
                long value = Math.abs(molecule.toScalarValue());
                permille = (int) ((value * energyPerMille) / 1000L);
            }
            return fixed + permille; // Add both if both are configured
        }
        
        /**
         * Calculates the entropy delta from configuration.
         * Positive values = entropy generation, negative values = entropy dissipation.
         * Both fixed and permille values are added if both are configured.
         */
        int calculateEntropyDelta(Molecule molecule) {
            int fixed = (entropyFixed != null) ? entropyFixed : 0;
            int permille = 0;
            if (entropyPerMille != null) {
                long value = Math.abs(molecule.toScalarValue());
                // entropyPerMille can be negative (dissipation) or positive (generation)
                permille = (int) ((value * entropyPerMille) / 1000L);
            }
            return fixed + permille; // Add both if both are configured
        }
    }

    private final Map<Integer, Rule> typeRules = new HashMap<>();

    @Override
    public void initialize(Config options) {
        // Direct type blocks under options (or poke-rules for PPK)
        for (String key : options.root().keySet()) {
            if ("_default".equalsIgnoreCase(key)) {
                Config typeConfig = options.getConfig(key);
                typeRules.put(DEFAULT_TYPE_KEY, new Rule(typeConfig));
            } else {
                Optional<Integer> typeConstant = Molecule.getTypeConstantByName(key);
                if (typeConstant.isPresent()) {
                    Config typeConfig = options.getConfig(key);
                    typeRules.put(typeConstant.get(), new Rule(typeConfig));
                } else {
                    LOG.warn("Unknown molecule type '{}' in PokeThermodynamicPolicy config will be ignored.", key);
                }
            }
        }
    }

    @Override
    public int getEnergyCost(ThermodynamicContext context) {
        // A write that lost its conflict never happens and therefore costs nothing
        ConflictResolutionStatus status = context.instruction().getConflictStatus();
        if (status != ConflictResolutionStatus.WON_EXECUTION && status != ConflictResolutionStatus.NOT_APPLICABLE) {
            return 0;
        }
        
        if (!writeCharged(context)) {
            // Target is occupied, POKE will fail - no cost
            return 0;
        }

        Molecule toWrite = getMoleculeToWrite(context.resolvedOperands());
        if (toWrite != null) {
            Rule rule = typeRules.get(toWrite.type());
            if (rule == null) {
                rule = typeRules.get(DEFAULT_TYPE_KEY);
            }
            
            if (rule != null) {
                return rule.calculateEnergy(toWrite);
            }
        }
        
        return 0;
    }

    @Override
    public int getEntropyDelta(ThermodynamicContext context) {
        // POKE only dissipates entropy, does NOT generate entropy from energy cost.
        // Configuration: positive entropy = generation, negative entropy = dissipation.
        // For POKE, entropy values should be negative (dissipation).

        // A write that lost its conflict never happens and therefore dissipates nothing
        ConflictResolutionStatus status = context.instruction().getConflictStatus();
        if (status != ConflictResolutionStatus.WON_EXECUTION && status != ConflictResolutionStatus.NOT_APPLICABLE) {
            return 0;
        }

        if (!writeCharged(context)) {
            // Target is occupied, POKE will fail - a failed write dissipates nothing either
            return 0;
        }

        Molecule toWrite = getMoleculeToWrite(context.resolvedOperands());
        if (toWrite != null) {
            Rule rule = typeRules.get(toWrite.type());
            if (rule == null) {
                rule = typeRules.get(DEFAULT_TYPE_KEY);
            }
            
            if (rule != null) {
                // Return only the configured entropy delta (negative = dissipation)
                return rule.calculateEntropyDelta(toWrite);
            } else {
                // No rule found - entropy must be explicitly configured
                throw new IllegalStateException("No entropy rule found for POKE instruction: moleculeType=" + toWrite.type() + ". Entropy must be explicitly configured in evochora.conf.");
            }
        }
        
        return 0;
    }
    
    /**
     * Reports whether write costs are charged: PPK instructions always pay (their PEEK
     * clears the cell first), any other write pays only when the target cell is empty,
     * because a write onto an occupied cell fails in execution and a failed instruction
     * carries no write thermodynamics - neither energy nor entropy.
     */
    private boolean writeCharged(ThermodynamicContext context) {
        String instructionName = context.instruction().getName();
        boolean isPPK = "PPKR".equals(instructionName) || "PPKI".equals(instructionName) || "PPKS".equals(instructionName);
        if (isPPK) {
            return true;
        }
        return context.targetInfo().isEmpty() || context.targetInfo().get().molecule().isEmpty();
    }

    private Molecule getMoleculeToWrite(List<Operand> operands) {
        if (operands != null && !operands.isEmpty()) {
            // For POKE/POKI/POKS, the value to write is always the first operand.
            // For PPK*, the first operand is also the value to write (after the peek).
            Object value = operands.get(0).value();
            if (value instanceof Integer) {
                return Molecule.fromInt((Integer) value);
            }
        }
        return null;
    }
}
