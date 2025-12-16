package org.evochora.runtime.thermodynamics.impl;

import com.typesafe.config.Config;
import org.evochora.runtime.isa.Instruction.ConflictResolutionStatus;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;
import org.evochora.runtime.spi.thermodynamics.ThermodynamicContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A flexible policy for PEEK-like instructions that depend on the target cell's content and ownership.
 * <p>
 * Important: PEEK generates entropy (positive values) based on energy consumption.
 * According to ASSEMBLY_SPEC.md, entropy generation equals energy cost (1:1 relationship).
 * <p>
 * Configuration structure:
 * <pre>
 * own: {
 *   ENERGY: { energy = 0, entropy = 0 },
 *   _default: { energy = 0, entropy = 0 }
 * },
 * foreign: {
 *   ENERGY: { energy-permille = -1000, entropy-permille = 0 },
 *   _default: { energy = 5, entropy = 5 }
 * },
 * unowned: { ... }
 * </pre>
 * <p>
 * Entropy must be explicitly configured for each ownership/molecule-type combination.
 */
public class PeekThermodynamicPolicy implements IThermodynamicPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(PeekThermodynamicPolicy.class);
    
    // Placeholder key for the default rule in the map
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
                throw new IllegalStateException("Entropy not configured for PeekThermodynamicPolicy rule. Must specify either 'entropy' or 'entropy-permille' (or both) in evochora.conf.");
            }
        }

        int calculateEnergy(Molecule molecule) {
            int fixed = energyFixed;
            int permille = 0;
            if (energyPerMille != 0) {
                long value = Math.abs(molecule.toScalarValue());
                // Use long arithmetic to prevent overflow before division
                permille = (int) ((value * energyPerMille) / 1000L);
            }
            return fixed + permille; // Add both if both are configured
        }
        
        int calculateEntropy(Molecule molecule, int energyCost) {
            int fixed = (entropyFixed != null) ? entropyFixed : 0;
            int permille = 0;
            if (entropyPerMille != null) {
                long value = Math.abs(molecule.toScalarValue());
                permille = (int) ((value * entropyPerMille) / 1000L);
            }
            return fixed + permille; // Add both if both are configured
        }
    }

    private enum Ownership { OWN, FOREIGN, UNOWNED }

    private final Map<Ownership, Map<Integer, Rule>> rules = new EnumMap<>(Ownership.class);

    @Override
    public void initialize(Config options) {
        for (Ownership ownership : Ownership.values()) {
            String ownerKey = ownership.name().toLowerCase();
            if (!options.hasPath(ownerKey)) continue;

            Config ownerConfig = options.getConfig(ownerKey);
            Map<Integer, Rule> typeRules = new HashMap<>();

            for (String typeName : ownerConfig.root().keySet()) {
                if ("_default".equalsIgnoreCase(typeName)) {
                    typeRules.put(DEFAULT_TYPE_KEY, new Rule(ownerConfig.getConfig(typeName)));
                } else {
                    Optional<Integer> typeConstant = Molecule.getTypeConstantByName(typeName);
                    if (typeConstant.isPresent()) {
                        typeRules.put(typeConstant.get(), new Rule(ownerConfig.getConfig(typeName)));
                    } else {
                        LOG.warn("Unknown molecule type '{}' in PeekThermodynamicPolicy config for '{}' will be ignored.", typeName, ownerKey);
                    }
                }
            }
            rules.put(ownership, typeRules);
        }
    }

    @Override
    public int getEnergyCost(ThermodynamicContext context) {
        // If the instruction lost a conflict (e.g., target occupied for POKE, or failed for other reasons),
        // we generally charge no execution cost in the legacy model.
        // For PEEK, conflict status isn't usually set to LOST unless something fundamental failed.
        ConflictResolutionStatus status = context.instruction().getConflictStatus();
        if (status != ConflictResolutionStatus.WON_EXECUTION && status != ConflictResolutionStatus.NOT_APPLICABLE) {
            return 0;
        }

        // If there's no target info (e.g. invalid operands), we fall back to 0 cost (or base cost if we had one)
        if (context.targetInfo().isEmpty()) {
            return 0;
        }

        var target = context.targetInfo().get();
        var organism = context.organism();

        Ownership ownership;
        if (target.ownerId() == organism.getId()) {
            ownership = Ownership.OWN;
        } else if (target.ownerId() == 0) {
            ownership = Ownership.UNOWNED;
        } else {
            ownership = Ownership.FOREIGN;
        }

        Map<Integer, Rule> typeRules = rules.get(ownership);
        if (typeRules != null) {
            Rule rule = typeRules.get(target.molecule().type());
            if (rule == null) {
                rule = typeRules.get(DEFAULT_TYPE_KEY);
            }
            
            if (rule != null) {
                return rule.calculateEnergy(target.molecule());
            }
        }

        // Default safe fallback if no rules match
        return 0;
    }

    @Override
    public int getEntropyDelta(ThermodynamicContext context) {
        ConflictResolutionStatus status = context.instruction().getConflictStatus();
        if (status != ConflictResolutionStatus.WON_EXECUTION && status != ConflictResolutionStatus.NOT_APPLICABLE) {
            return 0;
        }

        if (context.targetInfo().isEmpty()) {
            return 0;
        }

        var target = context.targetInfo().get();
        var organism = context.organism();

        Ownership ownership;
        if (target.ownerId() == organism.getId()) {
            ownership = Ownership.OWN;
        } else if (target.ownerId() == 0) {
            ownership = Ownership.UNOWNED;
        } else {
            ownership = Ownership.FOREIGN;
        }

        int energyCost = getEnergyCost(context);
        
        Map<Integer, Rule> typeRules = rules.get(ownership);
        if (typeRules != null) {
            Rule rule = typeRules.get(target.molecule().type());
            if (rule == null) {
                rule = typeRules.get(DEFAULT_TYPE_KEY);
            }
            
            if (rule != null) {
                return rule.calculateEntropy(target.molecule(), energyCost);
            }
        }
        
        // No rule found - entropy must be explicitly configured
        throw new IllegalStateException("No entropy rule found for PEEK instruction: ownership=" + ownership + ", moleculeType=" + target.molecule().type() + ". Entropy must be explicitly configured in evochora.conf.");
    }
}

