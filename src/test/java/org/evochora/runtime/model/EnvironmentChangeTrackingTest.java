package org.evochora.runtime.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Environment change tracking (delta compression support).
 */
@Tag("unit")
class EnvironmentChangeTrackingTest {
    
    private Environment env;
    
    @BeforeEach
    void setUp() {
        // 10x10 environment = 100 cells
        env = new Environment(new int[]{10, 10}, false);
    }
    
    // ========================================================================
    // Basic Change Tracking
    // ========================================================================
    
    @Test
    void newEnvironment_hasNoChanges() {
        BitSet changes = env.getChangedIndices();
        assertTrue(changes.isEmpty());
    }
    
    @Test
    void setMolecule_tracksChange() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, new int[]{5, 5});
        
        BitSet changes = env.getChangedIndices();
        assertEquals(1, changes.cardinality());
        
        // Flat index for (5,5) in 10x10 grid = 5*10 + 5 = 55
        assertTrue(changes.get(55));
    }
    
    @Test
    void setMoleculeWithOwner_tracksChange() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, 1, new int[]{3, 7});
        
        BitSet changes = env.getChangedIndices();
        assertEquals(1, changes.cardinality());
        
        // Flat index for (3,7) = 3*10 + 7 = 37
        assertTrue(changes.get(37));
    }
    
    @Test
    void setOwnerId_tracksChange() {
        env.setOwnerId(5, new int[]{2, 3});
        
        BitSet changes = env.getChangedIndices();
        assertEquals(1, changes.cardinality());
        
        // Flat index for (2,3) = 2*10 + 3 = 23
        assertTrue(changes.get(23));
    }
    
    @Test
    void multipleChanges_trackAll() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, new int[]{0, 0});
        env.setMolecule(mol, new int[]{1, 1});
        env.setMolecule(mol, new int[]{2, 2});
        
        BitSet changes = env.getChangedIndices();
        assertEquals(3, changes.cardinality());
        assertTrue(changes.get(0));   // (0,0) = 0
        assertTrue(changes.get(11));  // (1,1) = 11
        assertTrue(changes.get(22));  // (2,2) = 22
    }
    
    // ========================================================================
    // Reset Behavior
    // ========================================================================
    
    @Test
    void resetChangeTracking_clearsAllChanges() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, new int[]{0, 0});
        env.setMolecule(mol, new int[]{5, 5});
        assertEquals(2, env.getChangedIndices().cardinality());
        
        env.resetChangeTracking();
        
        assertTrue(env.getChangedIndices().isEmpty());
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
        
        BitSet changes = env.getChangedIndices();
        assertEquals(1, changes.cardinality());
        assertTrue(changes.get(55));  // Only new change
        assertFalse(changes.get(0));  // Old change not tracked
    }
    
    // ========================================================================
    // Edge Cases
    // ========================================================================
    
    @Test
    void sameCellMultipleTimes_onlyOneBitSet() {
        Molecule mol1 = Molecule.fromInt(100);
        Molecule mol2 = Molecule.fromInt(200);
        
        env.setMolecule(mol1, new int[]{5, 5});
        env.setMolecule(mol2, new int[]{5, 5});
        env.setMolecule(mol1, new int[]{5, 5});
        
        BitSet changes = env.getChangedIndices();
        assertEquals(1, changes.cardinality());
    }
    
    @Test
    void clearCell_isAlsoAChange() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, new int[]{5, 5});
        env.resetChangeTracking();
        
        // Clear cell (set to 0)
        Molecule empty = Molecule.fromInt(0);
        env.setMolecule(empty, new int[]{5, 5});
        
        BitSet changes = env.getChangedIndices();
        assertEquals(1, changes.cardinality());
        assertTrue(changes.get(55));
    }
    
    @Test
    void clearOwner_tracksChange() {
        env.setOwnerId(5, new int[]{3, 3});
        env.resetChangeTracking();
        
        env.clearOwner(new int[]{3, 3});
        
        BitSet changes = env.getChangedIndices();
        assertEquals(1, changes.cardinality());
        assertTrue(changes.get(33));
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
        BitSet changes = env.getChangedIndices();
        assertEquals(2, changes.cardinality());
        assertTrue(changes.get(0));   // (0,0)
        assertTrue(changes.get(11));  // (1,1)
        assertFalse(changes.get(22)); // (2,2) has different marker
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
        BitSet changes = env.getChangedIndices();
        assertEquals(3, changes.cardinality());
    }
    
    // ========================================================================
    // getTotalCells
    // ========================================================================
    
    @Test
    void getTotalCells_returnsCorrectValue() {
        assertEquals(100, env.getTotalCells());  // 10x10
        
        Environment env3d = new Environment(new int[]{5, 6, 7}, false);
        assertEquals(210, env3d.getTotalCells());  // 5*6*7
    }
    
    // ========================================================================
    // Integration with Flat Index Access
    // ========================================================================
    
    @Test
    void changedIndices_matchFlatIndexAccess() {
        Molecule mol = Molecule.fromInt(100);
        env.setMolecule(mol, 1, new int[]{3, 7});
        
        BitSet changes = env.getChangedIndices();
        int changedIndex = changes.nextSetBit(0);
        
        // Verify we can read the changed cell using flat index
        assertEquals(100, env.getMoleculeInt(changedIndex));
        assertEquals(1, env.getOwnerIdByIndex(changedIndex));
    }
}
