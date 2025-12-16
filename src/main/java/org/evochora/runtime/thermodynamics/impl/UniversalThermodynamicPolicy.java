package org.evochora.runtime.thermodynamics.impl;

import com.typesafe.config.Config;
import org.evochora.runtime.isa.Instruction.ConflictResolutionStatus;
import org.evochora.runtime.isa.Instruction.Operand;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;
import org.evochora.runtime.spi.thermodynamics.ThermodynamicContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A universal thermodynamic policy that supports base values, read rules, and write rules.
 * <p>
 * This policy automatically applies the appropriate rules based on the instruction context:
 * <ul>
 *   <li><strong>Base values</strong>: Always added to the final cost/entropy delta</li>
 *   <li><strong>Read rules</strong>: Applied when the instruction has a target cell (e.g., PEEK, SCAN)</li>
 *   <li><strong>Write rules</strong>: Applied when the instruction writes a molecule (e.g., POKE)</li>
 *   <li><strong>Both</strong>: Can be combined for instructions that both read and write (e.g., PPK)</li>
 * </ul>
 * <p>
 * Configuration structure:
 * <pre>
 * base-energy = 1          # Always added
 * base-entropy = 1         # Always added
 * read-rules: {
 *   own: { ... }
 *   foreign: { ... }
 *   unowned: { ... }
 * }
 * write-rules: {
 *   ENERGY: { ... }
 *   CODE: { ... }
 *   DATA: { ... }
 * }
 * </pre>
 */
public class UniversalThermodynamicPolicy implements IThermodynamicPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(UniversalThermodynamicPolicy.class);
    
    // Placeholder key for the default rule in maps
    private static final int DEFAULT_TYPE_KEY = -1;

    /**
     * Rule for calculating energy and entropy based on molecule values.
     * Supports both fixed values and permille-based proportional values.
     */
    private static class Rule {
        final int energyFixed;
        final int energyPerMille;
        final Integer entropyFixed;  // null if not configured
        final Integer entropyPerMille;  // null if not configured

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
                throw new IllegalStateException("Entropy not configured for UniversalThermodynamicPolicy rule. Must specify either 'entropy' or 'entropy-permille' (or both) in evochora.conf.");
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
        
        int calculateEntropy(Molecule molecule) {
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

    private enum Ownership { OWN, FOREIGN, UNOWNED }

    // Base values (always added)
    private int baseEnergy = 0;
    private int baseEntropy = 0;

    // Read rules (applied when targetInfo is present)
    private final Map<Ownership, Map<Integer, Rule>> readRules = new EnumMap<>(Ownership.class);

    // Write rules (applied when a molecule is being written)
    private final Map<Integer, Rule> writeRules = new HashMap<>();

    @Override
    public void initialize(Config options) {
        // Parse base values
        this.baseEnergy = options.hasPath("base-energy") ? options.getInt("base-energy") : 0;
        this.baseEntropy = options.hasPath("base-entropy") ? options.getInt("base-entropy") : 0;

        // Parse read-rules (if present)
        if (options.hasPath("read-rules")) {
            Config readRulesConfig = options.getConfig("read-rules");
            for (Ownership ownership : Ownership.values()) {
                String ownerKey = ownership.name().toLowerCase();
                if (!readRulesConfig.hasPath(ownerKey)) continue;

                Config ownerConfig = readRulesConfig.getConfig(ownerKey);
                Map<Integer, Rule> typeRules = new HashMap<>();

                for (String typeName : ownerConfig.root().keySet()) {
                    if ("_default".equalsIgnoreCase(typeName)) {
                        typeRules.put(DEFAULT_TYPE_KEY, new Rule(ownerConfig.getConfig(typeName)));
                    } else {
                        Optional<Integer> typeConstant = Molecule.getTypeConstantByName(typeName);
                        if (typeConstant.isPresent()) {
                            typeRules.put(typeConstant.get(), new Rule(ownerConfig.getConfig(typeName)));
                        } else {
                            LOG.warn("Unknown molecule type '{}' in UniversalThermodynamicPolicy read-rules for '{}' will be ignored.", typeName, ownerKey);
                        }
                    }
                }
                readRules.put(ownership, typeRules);
            }
        }

        // Parse write-rules (if present)
        if (options.hasPath("write-rules")) {
            Config writeRulesConfig = options.getConfig("write-rules");
            for (String key : writeRulesConfig.root().keySet()) {
                if ("_default".equalsIgnoreCase(key)) {
                    Config typeConfig = writeRulesConfig.getConfig(key);
                    writeRules.put(DEFAULT_TYPE_KEY, new Rule(typeConfig));
                } else {
                    Optional<Integer> typeConstant = Molecule.getTypeConstantByName(key);
                    if (typeConstant.isPresent()) {
                        Config typeConfig = writeRulesConfig.getConfig(key);
                        writeRules.put(typeConstant.get(), new Rule(typeConfig));
                    } else {
                        LOG.warn("Unknown molecule type '{}' in UniversalThermodynamicPolicy write-rules will be ignored.", key);
                    }
                }
            }
        }
    }

    @Override
    public int getEnergyCost(ThermodynamicContext context) {
        int total = baseEnergy; // Always start with base energy

        ConflictResolutionStatus status = context.instruction().getConflictStatus();
        if (status != ConflictResolutionStatus.WON_EXECUTION && status != ConflictResolutionStatus.NOT_APPLICABLE) {
            // Instruction lost conflict or failed - only return base energy (if any)
            return total;
        }

        // Apply read-rules if targetInfo is present
        if (context.targetInfo().isPresent() && !readRules.isEmpty()) {
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

            Map<Integer, Rule> typeRules = readRules.get(ownership);
            if (typeRules != null) {
                Rule rule = typeRules.get(target.molecule().type());
                if (rule == null) {
                    rule = typeRules.get(DEFAULT_TYPE_KEY);
                }
                
                if (rule != null) {
                    total += rule.calculateEnergy(target.molecule());
                }
            }
        }

        // Apply write-rules if a molecule is being written
        Molecule toWrite = getMoleculeToWrite(context.resolvedOperands());
        if (toWrite != null && !writeRules.isEmpty()) {
            // Check if target is already occupied (for POKE-like instructions)
            // Exception: PPK instructions first execute PEEK which clears the cell,
            // so POKE will always succeed - we should charge the cost even if target appears occupied.
            String instructionName = context.instruction().getName();
            boolean isPPK = "PPKR".equals(instructionName) || "PPKI".equals(instructionName) || "PPKS".equals(instructionName);
            
            if (!isPPK && context.targetInfo().isPresent()) {
                var target = context.targetInfo().get();
                if (!target.molecule().isEmpty()) {
                    // Target is occupied, write will fail - don't add write costs
                    return total;
                }
            }

            Rule rule = writeRules.get(toWrite.type());
            if (rule == null) {
                rule = writeRules.get(DEFAULT_TYPE_KEY);
            }
            
            if (rule != null) {
                total += rule.calculateEnergy(toWrite);
            }
        }

        return total;
    }

    @Override
    public int getEntropyDelta(ThermodynamicContext context) {
        int total = baseEntropy; // Always start with base entropy

        ConflictResolutionStatus status = context.instruction().getConflictStatus();
        if (status != ConflictResolutionStatus.WON_EXECUTION && status != ConflictResolutionStatus.NOT_APPLICABLE) {
            // Instruction lost conflict or failed - only return base entropy (if any)
            return total;
        }

        // Apply read-rules if targetInfo is present
        if (context.targetInfo().isPresent() && !readRules.isEmpty()) {
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

            Map<Integer, Rule> typeRules = readRules.get(ownership);
            if (typeRules != null) {
                Rule rule = typeRules.get(target.molecule().type());
                if (rule == null) {
                    rule = typeRules.get(DEFAULT_TYPE_KEY);
                }
                
                if (rule != null) {
                    total += rule.calculateEntropy(target.molecule());
                }
            }
        }

        // Apply write-rules if a molecule is being written
        Molecule toWrite = getMoleculeToWrite(context.resolvedOperands());
        if (toWrite != null && !writeRules.isEmpty()) {
            Rule rule = writeRules.get(toWrite.type());
            if (rule == null) {
                rule = writeRules.get(DEFAULT_TYPE_KEY);
            }
            
            if (rule != null) {
                total += rule.calculateEntropy(toWrite);
            }
        }

        return total;
    }
    
    /**
     * Extracts the molecule to be written from the resolved operands.
     * For POKE/POKI/POKS and PPK* instructions, the first operand contains the molecule to write.
     */
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

