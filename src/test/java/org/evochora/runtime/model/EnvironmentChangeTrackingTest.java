package org.evochora.runtime.model;

import org.evochora.runtime.label.PreExpandedHammingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Environment change tracking (delta compression support).
 */
@Tag("unit")
class EnvironmentChangeTrackingTest {
    
    private Environment env;
    
    @BeforeEach
    void setUp() {
        // 32x32 environment = 1024 cells
        env = new Environment(new int[]{32, 32}, false);
    }
    
    // ========================================================================
    // Basic Change Tracking
    // ========================================================================
    
    /**
     * Whether the cells changed since the last sample include the one at the given coordinate,
     * compared through the flat index the environment hands out, whatever its memory layout.
     */
    private boolean containsCell(List<Integer> changes, int x, int y) {
        return changes.contains(env.properties.toFlatIndex(new int[]{x, y}));
    }

    /** The flat indices of the cells changed since the last sample, as the environment hands them out. */
    private List<Integer> changed() {
        List<Integer> out = new ArrayList<>();
        env.forEachCellChangedSinceLastSample((flatIndex, molecule, owner) -> out.add(flatIndex));
        return out;
    }

    @Test
    void newEnvironment_hasNoChanges() {
        List<Integer> changes = changed();
        assertTrue(changes.isEmpty());
    }
    
    @Test
    void setMolecule_tracksChange() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, new int[]{5, 5});
        
        List<Integer> changes = changed();
        assertEquals(1, changes.size());
        
        assertTrue(containsCell(changes, 5, 5));
    }
    
    @Test
    void setMoleculeWithOwner_tracksChange() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, 1, new int[]{3, 7});
        
        List<Integer> changes = changed();
        assertEquals(1, changes.size());
        
        assertTrue(containsCell(changes, 3, 7));
    }
    
    @Test
    void setOwnerId_tracksChange() {
        env.setOwnerId(5, new int[]{2, 3});
        
        List<Integer> changes = changed();
        assertEquals(1, changes.size());
        
        assertTrue(containsCell(changes, 2, 3));
    }
    
    @Test
    void multipleChanges_trackAll() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, new int[]{0, 0});
        env.setMolecule(mol, new int[]{1, 1});
        env.setMolecule(mol, new int[]{2, 2});
        
        List<Integer> changes = changed();
        assertEquals(3, changes.size());
        assertTrue(containsCell(changes, 0, 0));
        assertTrue(containsCell(changes, 1, 1));
        assertTrue(containsCell(changes, 2, 2));
    }
    
    // ========================================================================
    // Reset Behavior
    // ========================================================================
    
    @Test
    void resetChangeTracking_clearsAllChanges() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, new int[]{0, 0});
        env.setMolecule(mol, new int[]{5, 5});
        assertEquals(2, changed().size());
        
        env.resetChangeTracking();
        
        assertTrue(changed().isEmpty());
    }
    
    @Test
    void changesAfterReset_trackNewChangesOnly() {
        Molecule mol = Molecule.fromInt(100);
        
        // First batch of changes
        env.setMolecule(mol, new int[]{0, 0});
        env.setMolecule(mol, new int[]{1, 1});
        env.resetChangeTracking();
        
        // Second batch of changes
        env.setMolecule(mol, new int[]{5, 5});
        
        List<Integer> changes = changed();
        assertEquals(1, changes.size());
        assertTrue(containsCell(changes, 5, 5));  // Only new change
        assertFalse(containsCell(changes, 0, 0)); // Old change not tracked
    }
    
    // ========================================================================
    // Edge Cases
    // ========================================================================
    
    @Test
    void sameCellMultipleTimes_isOneChange() {
        Molecule mol1 = Molecule.fromInt(100);
        Molecule mol2 = Molecule.fromInt(200);
        
        env.setMolecule(mol1, new int[]{5, 5});
        env.setMolecule(mol2, new int[]{5, 5});
        env.setMolecule(mol1, new int[]{5, 5});
        
        List<Integer> changes = changed();
        assertEquals(1, changes.size());
    }
    
    @Test
    void clearCell_isAlsoAChange() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, new int[]{5, 5});
        env.resetChangeTracking();
        
        // Clear cell (set to 0)
        Molecule empty = Molecule.fromInt(0);
        env.setMolecule(empty, new int[]{5, 5});
        
        List<Integer> changes = changed();
        assertEquals(1, changes.size());
        assertTrue(containsCell(changes, 5, 5));
    }
    
    @Test
    void clearOwner_tracksChange() {
        env.setOwnerId(5, new int[]{3, 3});
        env.resetChangeTracking();
        
        env.clearOwner(new int[]{3, 3});
        
        List<Integer> changes = changed();
        assertEquals(1, changes.size());
        assertTrue(containsCell(changes, 3, 3));
    }
    
    // ========================================================================
    // Bulk Operations
    // ========================================================================
    
    @Test
    void transferOwnership_tracksAllTransferredCells() {
        // Set up cells owned by organism 1 with marker 5
        // Molecule(type, value, marker) constructor
        env.setMolecule(new Molecule(1, 100, 5), 1, new int[]{0, 0});
        env.setMolecule(new Molecule(1, 100, 5), 1, new int[]{1, 1});
        env.setMolecule(new Molecule(1, 100, 3), 1, new int[]{2, 2});  // Different marker
        env.resetChangeTracking();
        
        // Transfer cells with marker 5 from owner 1 to owner 2
        int transferred = env.transferOwnership(1, 2, 5);
        
        assertEquals(2, transferred);
        List<Integer> changes = changed();
        assertEquals(2, changes.size());
        assertTrue(containsCell(changes, 0, 0));
        assertTrue(containsCell(changes, 1, 1));
        assertFalse(containsCell(changes, 2, 2)); // different marker, not transferred
    }
    
    @Test
    void clearOwnershipFor_tracksAllClearedCells() {
        Molecule mol = Molecule.fromInt(100);
        
        // Set up cells owned by organism 1
        env.setMolecule(mol, 1, new int[]{0, 0});
        env.setMolecule(mol, 1, new int[]{1, 1});
        env.setMolecule(mol, 1, new int[]{2, 2});
        env.resetChangeTracking();
        
        // Clear ownership for organism 1
        int cleared = env.clearOwnershipFor(1);
        
        assertEquals(3, cleared);
        List<Integer> changes = changed();
        assertEquals(3, changes.size());
    }
    
    // ========================================================================
    // getTotalCells
    // ========================================================================
    
    @Test
    void getTotalCells_returnsCorrectValue() {
        assertEquals(1024, env.getTotalCells());  // 32x32
        
        // A world this small cannot be tiled; the row-major layout (tile side 1) keeps the test's cell count.
        Environment env3d = new Environment(new EnvironmentProperties(new int[]{5, 6, 7}, false), new PreExpandedHammingStrategy(), 1);
        assertEquals(210, env3d.getTotalCells());  // 5*6*7
    }
    
    // ========================================================================
    // Content of changed cells
    // ========================================================================
    
    @Test
    void changedCells_carryTheirContent() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, 1, new int[]{3, 7});
        
        int[] seen = {0, 0, 0};
        env.forEachCellChangedSinceLastSample((flatIndex, molecule, owner) -> { seen[0]++; seen[1] = molecule; seen[2] = owner; });

        // The changed cell is handed out with its content and owner
        assertEquals(1, seen[0]);
        assertEquals(100, seen[1]);
        assertEquals(1, seen[2]);
    }
}
