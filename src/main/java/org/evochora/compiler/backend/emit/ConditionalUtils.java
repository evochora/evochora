package org.evochora.compiler.backend.emit;

import java.util.Map;
import java.util.Set;

/**
 * A utility class for handling conditional instructions.
 */
public final class ConditionalUtils {

    private static final Map<String, String> NEGATED_OPCODES = Map.ofEntries(
        Map.entry("IFR", "INR"), Map.entry("INR", "IFR"),
        Map.entry("IFS", "INS"), Map.entry("INS", "IFS"),
        Map.entry("LTR", "GETR"), Map.entry("GETR", "LTR"),
        Map.entry("LTI", "GETI"), Map.entry("GETI", "LTI"),
        Map.entry("LTS", "GETS"), Map.entry("GETS", "LTS"),
        Map.entry("GTR", "LETR"), Map.entry("LETR", "GTR"),
        Map.entry("GTI", "LETI"), Map.entry("LETI", "GTI"),
        Map.entry("GTS", "LETS"), Map.entry("LETS", "GTS"),
        Map.entry("IFTR", "INTR"), Map.entry("INTR", "IFTR"),
        Map.entry("IFTI", "INTI"), Map.entry("INTI", "IFTI"),
        Map.entry("IFTS", "INTS"), Map.entry("INTS", "IFTS"),
        Map.entry("IFMR", "INMR"), Map.entry("INMR", "IFMR"),
        Map.entry("IFMI", "INMI"), Map.entry("INMI", "IFMI"),
        Map.entry("IFMS", "INMS"), Map.entry("INMS", "IFMS"),
        Map.entry("IFPR", "INPR"), Map.entry("INPR", "IFPR"),
        Map.entry("IFPI", "INPI"), Map.entry("INPI", "IFPI"),
        Map.entry("IFPS", "INPS"), Map.entry("INPS", "IFPS"),
        Map.entry("IFFR", "INFR"), Map.entry("INFR", "IFFR"),
        Map.entry("IFFI", "INFI"), Map.entry("INFI", "IFFI"),
        Map.entry("IFFS", "INFS"), Map.entry("INFS", "IFFS"),
        Map.entry("IFVR", "INVR"), Map.entry("INVR", "IFVR"),
        Map.entry("IFVI", "INVI"), Map.entry("INVI", "IFVI"),
        Map.entry("IFVS", "INVS"), Map.entry("INVS", "IFVS")
    );

    private static final Set<String> CONDITIONAL_OPCODES = NEGATED_OPCODES.keySet();

    private ConditionalUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Checks if the given opcode is a conditional instruction.
     * @param opcode The opcode to check.
     * @return True if the opcode is conditional, false otherwise.
     */
    public static boolean isConditional(String opcode) {
        return CONDITIONAL_OPCODES.contains(opcode);
    }

    /**
     * Returns the negated form of the given conditional opcode.
     * @param opcode The opcode to negate.
     * @return The negated opcode, or null if the opcode is not a conditional instruction.
     */
    public static String getNegatedOpcode(String opcode) {
        return NEGATED_OPCODES.get(opcode);
    }
}
