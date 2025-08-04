// src/main/java/org/evochora/assembler/DisassembledInstruction.java
package org.evochora.assembler;

import java.util.List;

/**
 * Repräsentiert eine vollständig disassemblierte Instruktion als strukturierte Daten.
 * Dies ist der Rückgabetyp für das spezialisierte Logging.
 */
public record DisassembledInstruction(
        String opcodeName,                       // Name des Opcodes (z.B. "SETL", "JUMP")
        List<DisassembledArgument> arguments,   // Liste der disassemblierten Argumente
        String resolvedTargetCoordinate,         // Optional: Für JUMP-Ziele, PEEK/POKE-Ziele als "[X|Y]"
        String instructionType                   // Der Typ der Zelle: "CODE", "DATA", "ENERGY", "STRUCTURE"
) {}