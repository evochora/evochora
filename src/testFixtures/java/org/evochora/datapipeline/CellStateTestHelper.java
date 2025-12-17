package org.evochora.datapipeline;

import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.runtime.Config;

public class CellStateTestHelper {

    /**
     * Creates a CellState builder with the molecule data correctly packed.
     * Use this helper to avoid manual bit shifting in tests.
     * 
     * @param flatIndex The flat index of the cell
     * @param ownerId The owner ID
     * @param type The molecule type (use Config.TYPE_* constants)
     * @param value The molecule value
     * @param marker The marker value (0-15, unshifted)
     * @return A pre-configured CellState.Builder
     */
    public static CellState.Builder createCellStateBuilder(int flatIndex, int ownerId, int type, int value, int marker) {
        return CellState.newBuilder()
                .setFlatIndex(flatIndex)
                .setOwnerId(ownerId)
                .setMoleculeData(createMoleculeData(type, value, marker));
    }

    /**
     * Creates a CellState with the molecule data correctly packed.
     */
    public static CellState createCellState(int flatIndex, int ownerId, int type, int value, int marker) {
        return createCellStateBuilder(flatIndex, ownerId, type, value, marker).build();
    }
    
    /**
     * Helper to create a cell with just type and value (marker=0, owner=0, index=0).
     * Useful for simple composition tests.
     */
    public static CellState createCell(int type, int value) {
        return createCellState(0, 0, type, value, 0);
    }

    /**
     * Helper to create a cell with just type (value=0, marker=0, owner=0, index=0).
     */
    public static CellState createCell(int type) {
        return createCellState(0, 0, type, 0, 0);
    }

    public static int createMoleculeData(int type, int value, int marker) {
        // Handle "unknown" types passed as raw integers (e.g. 99) by shifting them if they are small.
        // Config.TYPE_* constants are already shifted (e.g. 0x10000).
        // If type is small (<= 255), assume it's a raw type ID that needs shifting.
        int typePart = type;
        if (type <= 255 && type > 0) {
             typePart = type << Config.TYPE_SHIFT;
        }
        
        // Mask value to ensure it fits and handle negative numbers correctly
        int valuePart = value & Config.VALUE_MASK;
        
        int markerPart = (marker << Config.MARKER_SHIFT) & Config.MARKER_MASK;
        
        return typePart | valuePart | markerPart;
    }
}

