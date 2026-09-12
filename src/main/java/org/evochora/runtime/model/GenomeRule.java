package org.evochora.runtime.model;

import org.evochora.runtime.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which of an organism's molecules the genome hash leaves out.
 * <p>
 * The genome is what the analytics treat as an organism's heritable material: the cells a child
 * receives from its parent and by which two organisms are recognised as the same strain. Not every
 * cell an organism owns belongs to it. A {@code STATE} cell holds what the organism wrote for
 * itself while running — a counter, a threshold, a slot it reads back later — and its content
 * differs between copies of one genome, so including it would make every birth look like a
 * mutation. The primordial program likewise encloses itself in a {@code STRUCTURE:100} shell: a
 * wall it builds, not code it inherits.
 * <p>
 * Which molecules are left out is a parameter of the run, configured under the key
 * {@value #CONFIG_KEY} in the {@code organism} block of the runtime configuration and recorded with
 * the run's resolved configuration. An entry is either a bare type name, which excludes every
 * molecule of that type, or a molecule specification {@code TYPE:VALUE} as
 * {@link Molecule#parse(String)} reads it, which excludes only molecules of that type carrying
 * that value. Marker bits play no part: a cell is judged by its type and value alone.
 * <p>
 * <strong>Thread Safety:</strong> instances are immutable after construction and may be consulted
 * from any number of threads.
 */
public final class GenomeRule {

    /**
     * The configuration key under which the excluded molecules are listed.
     */
    public static final String CONFIG_KEY = "genome-exclude";

    /**
     * The entries applied when the configuration does not carry {@link #CONFIG_KEY}: an organism's
     * own working memory and the structural shell the primordial program builds around itself.
     */
    public static final List<String> DEFAULT_ENTRIES = List.of("STATE", "STRUCTURE:100");

    /**
     * The number of distinct raw type indices a packed molecule can carry.
     */
    private static final int RAW_TYPE_COUNT = 1 << Config.TYPE_BITS;

    /**
     * Bits of a packed molecule that identify it for this rule: type and value, never the marker.
     */
    private static final int TYPE_AND_VALUE_MASK = Config.TYPE_MASK | Config.VALUE_MASK;

    /**
     * Indexed by raw type index: whole types that are excluded regardless of their value.
     */
    private final boolean[] excludedTypes;

    /**
     * Indexed by raw type index: types for which at least one single value is excluded. It spares
     * the scan of {@link #excludedTypeValues} for every molecule of any other type.
     */
    private final boolean[] hasExcludedValues;

    /**
     * The excluded type-and-value combinations, as packed molecule integers without marker bits.
     */
    private final int[] excludedTypeValues;

    /**
     * Builds the rule from the configured entries.
     *
     * @param entries The molecules to leave out of the genome hash. Each entry is a molecule type
     *                name, which excludes every molecule of that type, or a {@code TYPE:VALUE}
     *                specification, which excludes molecules of that type with that value. An empty
     *                list excludes nothing.
     * @throws IllegalArgumentException if an entry names no registered molecule type or carries a
     *                                  value that cannot be read; the message names the
     *                                  configuration key and the offending entry.
     * @throws NullPointerException if {@code entries} or one of its elements is null.
     */
    public GenomeRule(List<String> entries) {
        this.excludedTypes = new boolean[RAW_TYPE_COUNT];
        this.hasExcludedValues = new boolean[RAW_TYPE_COUNT];
        List<Integer> typedValues = new ArrayList<>();

        for (String rawEntry : entries) {
            String entry = rawEntry.trim();
            if (entry.indexOf(':') >= 0) {
                Molecule molecule = parseTypedValue(entry);
                int rawType = rawTypeIndex(molecule.type());
                hasExcludedValues[rawType] = true;
                typedValues.add(molecule.toInt() & TYPE_AND_VALUE_MASK);
            } else {
                excludedTypes[rawTypeIndex(parseType(entry))] = true;
            }
        }

        this.excludedTypeValues = new int[typedValues.size()];
        for (int i = 0; i < typedValues.size(); i++) {
            this.excludedTypeValues[i] = typedValues.get(i);
        }
    }

    /**
     * Reports whether the genome hash leaves the given molecule out.
     *
     * @param moleculeInt The packed molecule integer; its marker bits are ignored.
     * @return true if the molecule is excluded from the genome, false if it is part of it.
     */
    public boolean excludes(int moleculeInt) {
        int rawType = (moleculeInt & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;
        if (excludedTypes[rawType]) {
            return true;
        }
        if (!hasExcludedValues[rawType]) {
            return false;
        }
        int typeAndValue = moleculeInt & TYPE_AND_VALUE_MASK;
        for (int excluded : excludedTypeValues) {
            if (excluded == typeAndValue) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a bare type name to its type constant.
     *
     * @param entry The entry as configured.
     * @return The molecule type constant.
     * @throws IllegalArgumentException if the name is not a registered molecule type.
     */
    private static int parseType(String entry) {
        try {
            return MoleculeTypeRegistry.nameToType(entry);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(invalidEntry(entry) + e.getMessage(), e);
        }
    }

    /**
     * Resolves a {@code TYPE:VALUE} entry to the molecule it denotes.
     *
     * @param entry The entry as configured.
     * @return The molecule whose type and value are excluded.
     * @throws IllegalArgumentException if the type name is unknown or the value cannot be read.
     */
    private static Molecule parseTypedValue(String entry) {
        try {
            return Molecule.parse(entry);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(invalidEntry(entry) + e.getMessage(), e);
        }
    }

    /**
     * Builds the prefix of a rejection message, naming the configuration key and the entry.
     *
     * @param entry The entry that could not be read.
     * @return The message prefix, ending in a separator before the cause.
     */
    private static String invalidEntry(String entry) {
        return "Invalid " + CONFIG_KEY + " entry '" + entry + "': ";
    }

    /**
     * Converts a molecule type constant into the dense index its type bits form.
     *
     * @param type The molecule type constant.
     * @return The raw type index, between 0 and {@link #RAW_TYPE_COUNT} - 1.
     */
    private static int rawTypeIndex(int type) {
        return (type & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;
    }
}
