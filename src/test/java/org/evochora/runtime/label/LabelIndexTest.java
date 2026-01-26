package org.evochora.runtime.label;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for LabelIndex.
 * <p>
 * Tests the integration of LabelIndex with the ILabelMatchingStrategy,
 * verifying correct handling of LABEL molecule changes.
 */
@Tag("unit")
class LabelIndexTest {

    private LabelIndex labelIndex;
    private Environment environment;
    private int[] callerCoords;

    @BeforeEach
    void setUp() {
        labelIndex = new LabelIndex();
        // Create a small test environment (64x64)
        EnvironmentProperties props = new EnvironmentProperties(new int[]{64, 64}, true);
        environment = new Environment(props);
        callerCoords = new int[]{0, 0};
    }

    @Test
    void testFindTargetWithNoLabels() {
        int result = labelIndex.findTarget(12345, 1, callerCoords, environment);
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void testAddAndFindExactMatch() {
        // Create a LABEL molecule with value 12345
        int labelValue = 12345;
        int flatIndex = 100;
        int owner = 1;

        // Simulate setMolecule with a LABEL
        int moleculeInt = Config.TYPE_LABEL | labelValue;
        labelIndex.onMoleculeSet(flatIndex, 0, moleculeInt, owner);

        // Find the label
        int result = labelIndex.findTarget(labelValue, owner, callerCoords, environment);
        assertThat(result).isEqualTo(flatIndex);
    }

    @Test
    void testRemoveLabel() {
        int labelValue = 12345;
        int flatIndex = 100;
        int owner = 1;

        int moleculeInt = Config.TYPE_LABEL | labelValue;
        labelIndex.onMoleculeSet(flatIndex, 0, moleculeInt, owner);

        // Verify it exists
        assertThat(labelIndex.findTarget(labelValue, owner, callerCoords, environment)).isEqualTo(flatIndex);

        // Remove it (simulate clearing the cell)
        labelIndex.onMoleculeSet(flatIndex, moleculeInt, 0, owner);

        // Should no longer be found
        assertThat(labelIndex.findTarget(labelValue, owner, callerCoords, environment)).isEqualTo(-1);
    }

    @Test
    void testReplaceLabel() {
        int flatIndex = 100;
        int owner = 1;

        int oldValue = 12345;
        int newValue = 54321;

        int oldMolecule = Config.TYPE_LABEL | oldValue;
        int newMolecule = Config.TYPE_LABEL | newValue;

        // Add first label
        labelIndex.onMoleculeSet(flatIndex, 0, oldMolecule, owner);
        assertThat(labelIndex.findTarget(oldValue, owner, callerCoords, environment)).isEqualTo(flatIndex);

        // Replace with new label
        labelIndex.onMoleculeSet(flatIndex, oldMolecule, newMolecule, owner);

        // Old value should not be found
        assertThat(labelIndex.findTarget(oldValue, owner, callerCoords, environment)).isEqualTo(-1);
        // New value should be found
        assertThat(labelIndex.findTarget(newValue, owner, callerCoords, environment)).isEqualTo(flatIndex);
    }

    @Test
    void testOwnerChange() {
        int labelValue = 12345;
        int flatIndex = 100;
        int oldOwner = 1;
        int newOwner = 2;

        int moleculeInt = Config.TYPE_LABEL | labelValue;
        labelIndex.onMoleculeSet(flatIndex, 0, moleculeInt, oldOwner);

        // Old owner can find it as "own"
        int result1 = labelIndex.findTarget(labelValue, oldOwner, callerCoords, environment);
        assertThat(result1).isEqualTo(flatIndex);

        // Change ownership
        labelIndex.onOwnerChange(flatIndex, moleculeInt, newOwner);

        // New owner can find it as "own"
        int result2 = labelIndex.findTarget(labelValue, newOwner, callerCoords, environment);
        assertThat(result2).isEqualTo(flatIndex);
    }

    @Test
    void testNonLabelMoleculeIgnored() {
        // DATA molecule should be ignored
        int dataMolecule = Config.TYPE_DATA | 12345;
        int flatIndex = 100;

        labelIndex.onMoleculeSet(flatIndex, 0, dataMolecule, 1);

        // Should not be found
        assertThat(labelIndex.findTarget(12345, 1, callerCoords, environment)).isEqualTo(-1);
    }

    @Test
    void testGetCandidates() {
        int labelValue = 12345;
        int flatIndex = 100;
        int owner = 1;

        int moleculeInt = Config.TYPE_LABEL | labelValue;
        labelIndex.onMoleculeSet(flatIndex, 0, moleculeInt, owner);

        var candidates = labelIndex.getCandidates(labelValue);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.iterator().next().flatIndex()).isEqualTo(flatIndex);
    }
}
