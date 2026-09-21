package org.evochora.runtime.thermodynamics.impl;

import com.typesafe.config.Config;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A universal thermodynamic policy that supports base values, read rules, and write rules.
 * <ul>
 *   <li><strong>Base values</strong>: charged for every execution, whatever it did</li>
 *   <li><strong>Read rules</strong>: charged for each molecule the instruction took out of a cell,
 *       resolved by the cell's ownership and the molecule's type and value</li>
 *   <li><strong>Write rules</strong>: charged for each molecule the instruction stored in a cell,
 *       resolved by that molecule's type and value</li>
 * </ul>
 * An instruction that both reads and writes, such as PPK, records both effects and is charged for
 * both; one that failed recorded neither and is charged its base values alone.
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
 * molecule type once during {@link #initialize(Config)}; per-effect lookups are plain array
 * accesses on the packed molecule, without unpacking it into an object.
 */
public class UniversalThermodynamicPolicy implements IThermodynamicPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(UniversalThermodynamicPolicy.class);

    private static final int DEFAULT_TYPE_KEY = -1;

    /** One slot per possible value of the molecule's type field. */
    private static final int TYPE_SLOTS = 1 << org.evochora.runtime.Config.TYPE_BITS;

    /**
     * Rule for calculating energy and entropy from a molecule's value.
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

        int calculateEnergy(int value) {
            int fixed = energyFixed;
            int permille = 0;
            if (energyPerMille != 0) {
                // Use long arithmetic to prevent overflow before division
                permille = (int) (((long) Math.abs(value) * energyPerMille) / 1000L);
            }
            return fixed + permille; // Add both if both are configured
        }

        int calculateEntropy(int value) {
            int fixed = (entropyFixed != null) ? entropyFixed : 0;
            int permille = 0;
            if (entropyPerMille != null) {
                // entropyPerMille can be negative (dissipation) or positive (generation)
                permille = (int) (((long) Math.abs(value) * entropyPerMille) / 1000L);
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
         * Resolves the applicable rule for a molecule value, checking value overrides first.
         *
         * @param value The molecule's signed value.
         * @return The most specific matching rule.
         */
        Rule resolve(int value) {
            if (valueOverrides != null) {
                Rule override = valueOverrides.get(value);
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
        // otherwise the type-level default, so per-effect resolution is one array access.
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

    /** Maps a packed molecule to the table slot of its type. */
    private static int typeSlot(int moleculeInt) {
        return (moleculeInt & org.evochora.runtime.Config.TYPE_MASK) >>> org.evochora.runtime.Config.TYPE_SHIFT;
    }

    /** Classifies a cell's owner relative to the organism that acted on it. */
    private static Ownership ownershipOf(int ownerId, int actorId) {
        if (ownerId == actorId) return Ownership.OWN;
        if (ownerId == 0) return Ownership.UNOWNED;
        return Ownership.FOREIGN;
    }

    @Override
    public int baseEnergy() {
        return baseEnergy;
    }

    @Override
    public int baseEntropy() {
        return baseEntropy;
    }

    @Override
    public Thermodynamics priceEffect(boolean isWrite, int moleculeInt, int ownerId, int actorId) {
        TypeRule typeRule;
        if (isWrite) {
            if (this.writeTable == null) {
                return FREE;
            }
            typeRule = this.writeTable[typeSlot(moleculeInt)];
        } else {
            if (this.readTable == null) {
                return FREE;
            }
            typeRule = this.readTable[ownershipOf(ownerId, actorId).ordinal()][typeSlot(moleculeInt)];
        }
        if (typeRule == null) {
            return FREE;
        }
        int value = Molecule.extractSignedValue(moleculeInt);
        Rule rule = typeRule.resolve(value);
        return new Thermodynamics(rule.calculateEnergy(value), rule.calculateEntropy(value));
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
}
