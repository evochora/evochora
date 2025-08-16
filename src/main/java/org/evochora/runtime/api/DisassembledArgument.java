package org.evochora.runtime.api;

/**
 * Repräsentiert ein einzelnes, zur Laufzeit disassembliertes Argument einer Instruktion.
 * Enthält Typ, Name (z.B. %DR0), den Rohwert und den vollen Anzeigenamen (z.B. DATA:42).
 */
public record DisassembledArgument(
        String type,
        String name,
        int value,
        String fullDisplayValue
) {}