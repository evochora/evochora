package org.evochora.server.indexer;

import org.evochora.runtime.model.IEnvironmentReader;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.Config;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.contracts.raw.RawCellState;

import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

/**
 * Implementation of IEnvironmentReader that reads from RawTickState data.
 * This allows the UnifiedDisassembler to work with debug data without
 * requiring access to the full Runtime Environment.
 */
public class RawTickStateReader implements IEnvironmentReader {
    
    private final Map<String, RawCellState> cellMap;
    private final int[] shape;
    private final EnvironmentProperties properties;
    
    /**
     * Creates a new reader for the given raw tick state.
     * 
     * @param rawTickState The raw tick state to read from
     * @param properties The environment properties for coordinate calculations
     */
    public RawTickStateReader(RawTickState rawTickState, EnvironmentProperties properties) {
        this.properties = properties;
        this.shape = properties.getWorldShape();
        
        // Convert List<RawCellState> to efficient Map for O(1) lookups
        this.cellMap = new HashMap<>();
        for (RawCellState cell : rawTickState.cells()) {
            String key = Arrays.toString(cell.pos());
            cellMap.put(key, cell);
        }
    }
    
    @Override
    public Molecule getMolecule(int[] coordinates) {
        // Check if coordinates are outside the world boundaries (like Environment does)
        if (coordinates.length != shape.length) {
            return Molecule.fromInt(0); // Invalid dimensions
        }
        for (int i = 0; i < coordinates.length; i++) {
            if (coordinates[i] < 0 || coordinates[i] >= shape[i]) {
                return Molecule.fromInt(0); // Outside boundaries
            }
        }
        
        // Check if cell exists in sparse data
        String key = Arrays.toString(coordinates);
        RawCellState cell = cellMap.get(key);
        return cell != null ? Molecule.fromInt(cell.molecule()) : Molecule.fromInt(0);
    }
    
    @Override
    public int[] getShape() {
        return shape;
    }
    
    @Override
    public EnvironmentProperties getProperties() {
        return properties;
    }
}
