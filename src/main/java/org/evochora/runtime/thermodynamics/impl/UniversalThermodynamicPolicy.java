package org.evochora.runtime.thermodynamics.impl;

import com.typesafe.config.Config;
import org.evochora.runtime.isa.Instruction;
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
 *   CODE: {
 *     energy = 5, entropy = -50          # Default for all CODE values
 *     values: {
 *       "0": { energy = 1, entropy = -50 }  # Override for CODE:0 (NOP)
 *     }
 *   }
 *   DATA: { ... }
 * }
 * </pre>
 * <p>
 * Each type rule supports an optional {@code values} sub-block for value-specific overrides.
 * When a molecule is evaluated, the policy first checks for a matching value override; if none
 * is found, it falls back to the type-level default rule.
 * <p>
 * This policy runs for every executed instruction, so rule resolution is on the simulation's
 * hot path. The parsed rules are therefore compiled into arrays indexed by ownership and
 * molecule type once during {@link #initialize(Config)}; per-instruction lookups are plain
 * array accesses without boxing, and {@link #getThermodynamics(ThermodynamicContext)} resolves
 * the context once for both the energy and the entropy result.
 */
public class UniversalThermodynamicPolicy implements IThermodynamicPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(UniversalThermodynamicPolicy.class);

    private static final int DEFAULT_TYPE_KEY = -1;

    /** One slot per possible value of the molecule's type field. */
    private static final int TYPE_SLOTS = 1 << org.evochora.runtime.Config.TYPE_BITS;

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

    /**
     * Groups a default {@link Rule} with optional value-specific overrides.
     * Resolution checks for a value-specific rule first, then falls back to the type default.
     */
    private static class TypeRule {
        final Rule defaultRule;
        final Map<Integer, Rule> valueOverrides;

        TypeRule(Rule defaultRule, Map<Integer, Rule> valueOverrides) {
            this.defaultRule = defaultRule;
            this.valueOverrides = valueOverrides;
        }

        /**
         * Resolves the applicable rule for a molecule, checking value overrides first.
         *
         * @param molecule The molecule to resolve a rule for.
         * @return The most specific matching rule.
         */
        Rule resolve(Molecule molecule) {
            if (valueOverrides != null) {
                Rule override = valueOverrides.get(molecule.toScalarValue());
                if (override != null) {
                    return override;
                }
            }
            return defaultRule;
        }
    }

    private enum Ownership { OWN, FOREIGN, UNOWNED }

    // Base values (always added)
    private int baseEnergy = 0;
    private int baseEntropy = 0;

    /**
     * Read rules compiled per ownership and molecule-type slot; the type-level default
     * rule is pre-filled into every slot without a specific rule. {@code null} when the
     * configuration has no read rules; a row is all-{@code null} when its ownership class
     * is not configured.
     */
    private TypeRule[][] readTable;

    /** Write rules compiled per molecule-type slot, defaults pre-filled; {@code null} when absent. */
    private TypeRule[] writeTable;

    /** Opcode ids of PPKR/PPKI/PPKS once resolved from the instruction registry. */
    private int[] ppkOpcodes;

    @Override
    public void initialize(Config options) {
        // Parse base values
        this.baseEnergy = options.hasPath("base-energy") ? options.getInt("base-energy") : 0;
        this.baseEntropy = options.hasPath("base-entropy") ? options.getInt("base-entropy") : 0;

        // Parse read-rules (if present)
        Map<Ownership, Map<Integer, TypeRule>> readRules = new EnumMap<>(Ownership.class);
        if (options.hasPath("read-rules")) {
            Config readRulesConfig = options.getConfig("read-rules");
            for (Ownership ownership : Ownership.values()) {
                String ownerKey = ownership.name().toLowerCase();
                if (!readRulesConfig.hasPath(ownerKey)) continue;
                Config ownerConfig = readRulesConfig.getConfig(ownerKey);
                Map<Integer, TypeRule> typeRules = new HashMap<>();
                for (String typeName : ownerConfig.root().keySet()) {
                    Config typeConfig = ownerConfig.getConfig(typeName);
                    if ("_default".equalsIgnoreCase(typeName)) {
                        typeRules.put(DEFAULT_TYPE_KEY, parseTypeRule(typeConfig));
                    } else {
                        Optional<Integer> typeConstant = Molecule.getTypeConstantByName(typeName);
                        if (typeConstant.isPresent()) {
                            typeRules.put(typeConstant.get(), parseTypeRule(typeConfig));
                        } else {
                            LOG.warn("Unknown molecule type '{}' in UniversalThermodynamicPolicy read-rules for '{}' will be ignored.", typeName, ownerKey);
                        }
                    }
                }
                readRules.put(ownership, typeRules);
            }
        }

        // Parse write-rules (if present)
        Map<Integer, TypeRule> writeRules = new HashMap<>();
        if (options.hasPath("write-rules")) {
            Config writeRulesConfig = options.getConfig("write-rules");
            for (String key : writeRulesConfig.root().keySet()) {
                Config typeConfig = writeRulesConfig.getConfig(key);
                if ("_default".equalsIgnoreCase(key)) {
                    writeRules.put(DEFAULT_TYPE_KEY, parseTypeRule(typeConfig));
                } else {
                    Optional<Integer> typeConstant = Molecule.getTypeConstantByName(key);
                    if (typeConstant.isPresent()) {
                        writeRules.put(typeConstant.get(), parseTypeRule(typeConfig));
                    } else {
                        LOG.warn("Unknown molecule type '{}' in UniversalThermodynamicPolicy write-rules will be ignored.", key);
                    }
                }
            }
        }

        // Compile the parsed rules into per-slot tables: specific rule where configured,
        // otherwise the type-level default, so per-instruction resolution is one array access.
        if (!readRules.isEmpty()) {
            this.readTable = new TypeRule[Ownership.values().length][];
            for (Map.Entry<Ownership, Map<Integer, TypeRule>> entry : readRules.entrySet()) {
                this.readTable[entry.getKey().ordinal()] = compileTypeTable(entry.getValue());
            }
            for (int i = 0; i < this.readTable.length; i++) {
                if (this.readTable[i] == null) {
                    this.readTable[i] = new TypeRule[TYPE_SLOTS];
                }
            }
        } else {
            this.readTable = null;
        }
        this.writeTable = writeRules.isEmpty() ? null : compileTypeTable(writeRules);
    }

    /** Expands a type-keyed rule map into one slot per possible molecule type. */
    private static TypeRule[] compileTypeTable(Map<Integer, TypeRule> typeRules) {
        TypeRule[] table = new TypeRule[TYPE_SLOTS];
        TypeRule defaultRule = typeRules.get(DEFAULT_TYPE_KEY);
        for (int slot = 0; slot < TYPE_SLOTS; slot++) {
            TypeRule specific = typeRules.get(slot << org.evochora.runtime.Config.TYPE_SHIFT);
            table[slot] = (specific != null) ? specific : defaultRule;
        }
        return table;
    }

    /** Maps a molecule's type field (already shifted) to its table slot. */
    private static int typeSlot(int type) {
        return (type & org.evochora.runtime.Config.TYPE_MASK) >>> org.evochora.runtime.Config.TYPE_SHIFT;
    }

    /** Classifies the target cell's owner relative to the executing organism. */
    private static Ownership ownershipOf(ThermodynamicContext.TargetInfo target, int organismId) {
        if (target.ownerId() == organismId) return Ownership.OWN;
        if (target.ownerId() == 0) return Ownership.UNOWNED;
        return Ownership.FOREIGN;
    }

    /**
     * Reports whether the instruction is one of the PPK variants, which are charged write
     * costs even on an occupied target because their preceding PEEK clears the cell.
     * The opcode ids are resolved from the instruction registry on first use; when the
     * registry is not initialized, the names are compared instead.
     */
    private boolean isPpk(org.evochora.runtime.isa.Instruction instruction) {
        int[] ids = this.ppkOpcodes;
        if (ids == null) {
            Integer r = Instruction.getInstructionIdByName("PPKR");
            Integer i = Instruction.getInstructionIdByName("PPKI");
            Integer s = Instruction.getInstructionIdByName("PPKS");
            if (r != null && i != null && s != null) {
                ids = new int[]{r, i, s};
                this.ppkOpcodes = ids;
            } else {
                String name = instruction.getName();
                return "PPKR".equals(name) || "PPKI".equals(name) || "PPKS".equals(name);
            }
        }
        int opcode = instruction.getFullOpcodeId();
        return opcode == ids[0] || opcode == ids[1] || opcode == ids[2];
    }

    /** Resolves the read rule applying to the context's target cell, or {@code null}. */
    private Rule readRuleFor(ThermodynamicContext context) {
        if (this.readTable == null || context.targetInfo().isEmpty()) {
            return null;
        }
        ThermodynamicContext.TargetInfo target = context.targetInfo().get();
        Ownership ownership = ownershipOf(target, context.organism().getId());
        TypeRule typeRule = this.readTable[ownership.ordinal()][typeSlot(target.molecule().type())];
        return (typeRule != null) ? typeRule.resolve(target.molecule()) : null;
    }

    /** Resolves the write rule applying to the given molecule, or {@code null}. */
    private Rule writeRuleFor(Molecule toWrite) {
        TypeRule typeRule = this.writeTable[typeSlot(toWrite.type())];
        return (typeRule != null) ? typeRule.resolve(toWrite) : null;
    }

    /**
     * Reports whether write costs are charged: PPK instructions always pay (their PEEK
     * clears the cell first); any other write pays only when the target cell is empty,
     * because a write onto an occupied cell fails in execution and a failed instruction
     * carries no write thermodynamics - neither energy nor entropy - only the error penalty.
     */
    private boolean writeCharged(ThermodynamicContext context) {
        if (isPpk(context.instruction())) {
            return true;
        }
        return context.targetInfo().isEmpty() || context.targetInfo().get().molecule().isEmpty();
    }

    @Override
    public Thermodynamics getThermodynamics(ThermodynamicContext context) {
        ConflictResolutionStatus status = context.instruction().getConflictStatus();
        if (status != ConflictResolutionStatus.WON_EXECUTION && status != ConflictResolutionStatus.NOT_APPLICABLE) {
            // Instruction lost conflict or failed - only the base values apply
            return new Thermodynamics(baseEnergy, baseEntropy);
        }

        int energy = baseEnergy;
        int entropy = baseEntropy;

        Rule readRule = readRuleFor(context);
        if (readRule != null) {
            Molecule molecule = context.targetInfo().get().molecule();
            energy += readRule.calculateEnergy(molecule);
            entropy += readRule.calculateEntropy(molecule);
        }

        if (this.writeTable != null) {
            Molecule toWrite = getMoleculeToWrite(context.resolvedOperands());
            if (toWrite != null && writeCharged(context)) {
                Rule writeRule = writeRuleFor(toWrite);
                if (writeRule != null) {
                    energy += writeRule.calculateEnergy(toWrite);
                    entropy += writeRule.calculateEntropy(toWrite);
                }
            }
        }
        return new Thermodynamics(energy, entropy);
    }

    @Override
    public int getEnergyCost(ThermodynamicContext context) {
        ConflictResolutionStatus status = context.instruction().getConflictStatus();
        if (status != ConflictResolutionStatus.WON_EXECUTION && status != ConflictResolutionStatus.NOT_APPLICABLE) {
            // Instruction lost conflict or failed - only return base energy (if any)
            return baseEnergy;
        }
        int total = baseEnergy;
        Rule readRule = readRuleFor(context);
        if (readRule != null) {
            total += readRule.calculateEnergy(context.targetInfo().get().molecule());
        }
        if (this.writeTable != null) {
            Molecule toWrite = getMoleculeToWrite(context.resolvedOperands());
            if (toWrite != null && writeCharged(context)) {
                Rule writeRule = writeRuleFor(toWrite);
                if (writeRule != null) {
                    total += writeRule.calculateEnergy(toWrite);
                }
            }
        }
        return total;
    }

    @Override
    public int getEntropyDelta(ThermodynamicContext context) {
        ConflictResolutionStatus status = context.instruction().getConflictStatus();
        if (status != ConflictResolutionStatus.WON_EXECUTION && status != ConflictResolutionStatus.NOT_APPLICABLE) {
            // Instruction lost conflict or failed - only return base entropy (if any)
            return baseEntropy;
        }
        int total = baseEntropy;
        Rule readRule = readRuleFor(context);
        if (readRule != null) {
            total += readRule.calculateEntropy(context.targetInfo().get().molecule());
        }
        if (this.writeTable != null) {
            Molecule toWrite = getMoleculeToWrite(context.resolvedOperands());
            if (toWrite != null && writeCharged(context)) {
                Rule writeRule = writeRuleFor(toWrite);
                if (writeRule != null) {
                    total += writeRule.calculateEntropy(toWrite);
                }
            }
        }
        return total;
    }

    /**
     * Parses a type-level rule from config, including optional value-specific overrides.
     * <p>
     * The config may contain a {@code values} sub-block with integer keys mapping to
     * value-specific rules. Example:
     * <pre>
     * CODE: {
     *   energy = 5, entropy = -50
     *   values: { "0": { energy = 1, entropy = -50 } }
     * }
     * </pre>
     *
     * @param config The config block for this type.
     * @return A TypeRule containing the default rule and any value overrides.
     */
    private TypeRule parseTypeRule(Config config) {
        Rule defaultRule = new Rule(config);
        Map<Integer, Rule> valueOverrides = null;
        if (config.hasPath("values")) {
            Config valuesConfig = config.getConfig("values");
            valueOverrides = new HashMap<>();
            for (String valueKey : valuesConfig.root().keySet()) {
                try {
                    int value = Integer.parseInt(valueKey);
                    valueOverrides.put(value, new Rule(valuesConfig.getConfig(valueKey)));
                } catch (NumberFormatException e) {
                    LOG.warn("Non-integer value key '{}' in UniversalThermodynamicPolicy values block will be ignored.", valueKey);
                }
            }
        }
        return new TypeRule(defaultRule, valueOverrides);
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
