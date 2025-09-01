package org.evochora.server.indexer;

import org.evochora.runtime.Config;

/**
 * Utility class for molecule type operations.
 * Provides shared functionality for converting type IDs to human-readable names.
 */
public final class MoleculeTypeUtils {
    
    private MoleculeTypeUtils() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Converts a molecule type ID to a human-readable name.
     * 
     * @param typeId The molecule type ID from Config
     * @return Human-readable name for the type, or "UNKNOWN" if not recognized
     */
    public static String typeIdToName(int typeId) {
        if (typeId == Config.TYPE_CODE) return "CODE";
        if (typeId == Config.TYPE_DATA) return "DATA";
        if (typeId == Config.TYPE_ENERGY) return "ENERGY";
        if (typeId == Config.TYPE_STRUCTURE) return "STRUCTURE";
        return "UNKNOWN";
    }
}
