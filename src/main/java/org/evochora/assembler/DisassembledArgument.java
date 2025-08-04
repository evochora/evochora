// src/main/java/org/evochora/assembler/DisassembledArgument.java
package org.evochora.assembler;

/**
 * Repräsentiert ein disassembliertes Argument mit seinem rohen Wert,
 * seinem aufgelösten (menschenlesbaren) Wert und seinem Typ.
 * Wird für das spezialisierte Logging verwendet.
 */
public record DisassembledArgument(
        int rawValue,        // Der ursprüngliche Integer-Wert des Arguments (z.B. 0 für %DR0, 123 für Literal 123)
        String resolvedValue, // Der aufgelöste Wert (z.B. "%DR0", "MY_LABEL", "123", "[1|0]")
        ArgumentType type    // Der Typ des Arguments (REGISTER, LABEL, LITERAL, COORDINATE)
) {}