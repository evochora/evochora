package org.evochora.runtime.services;

/**
 * Einfache Datenstruktur für Disassembly-Ergebnisse.
 * Enthält nur primitive Werte ohne Objekte für maximale Performance.
 */
public record DisassemblyData(
    int opcodeId,
    String opcodeName,
    int[] argValues,
    int[] argPositions
) {}
