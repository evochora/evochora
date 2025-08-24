package org.evochora.server.contracts.raw;

import java.io.Serializable;

/**
 * Repräsentiert den rohen Zustand einer einzelnen, nicht-leeren Zelle in der Welt.
 */
public record RawCellState(
        int[] pos,
        int molecule, // Der rohe 32-bit Integer-Wert des Moleküls
        int ownerId
) implements Serializable {}