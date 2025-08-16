package org.evochora.runtime.api;

import java.util.List;

/**
 * Repräsentiert eine einzelne, zur Laufzeit disassemblierte Instruktion.
 * Diese Klasse ist Teil der sauberen Runtime-API und hat keine Legacy-Abhängigkeiten.
 */
public record DisassembledInstruction(
        String opcodeName,
        List<DisassembledArgument> arguments
) {}