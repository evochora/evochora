package org.evochora.server.indexer;

import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.RawTickState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the RawTickStateReader class.
 * Tests the debug implementation of IEnvironmentReader.
 */
class RawTickStateReaderTest {

    @Test
    @Tag("unit")
    void getMolecule_existingCell() {
        // Arrange
        int[] worldShape = {10, 10};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, true);
        
        // Create test data
        RawCellState cell1 = new RawCellState(new int[]{5, 5}, 42, 0);
        RawCellState cell2 = new RawCellState(new int[]{3, 7}, 100, 1);
        List<RawCellState> cells = Arrays.asList(cell1, cell2);
        
        RawTickState rawTickState = new RawTickState(1L, List.of(), cells);
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act & Assert
        Molecule molecule1 = reader.getMolecule(new int[]{5, 5});
        assertThat(molecule1).isNotNull();
        assertThat(molecule1.toScalarValue()).isEqualTo(42);
        assertThat(molecule1.type()).isEqualTo(0);
        
        Molecule molecule2 = reader.getMolecule(new int[]{3, 7});
        assertThat(molecule2).isNotNull();
        assertThat(molecule2.toScalarValue()).isEqualTo(100);
        assertThat(molecule2.type()).isEqualTo(1);
    }

    @Test
    @Tag("unit")
    void getMolecule_nonExistentCell() {
        // Arrange
        int[] worldShape = {10, 10};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, true);
        
        // Create empty tick state with no cells
        RawTickState rawTickState = new RawTickState(1L, List.of(), List.of());
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act
        Molecule molecule = reader.getMolecule(new int[]{1, 1});
        
        // Assert
        assertThat(molecule).isNull();
    }

    @Test
    @Tag("unit")
    void getMolecule_emptyTickState() {
        // Arrange
        int[] worldShape = {10, 10};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, true);
        
        // Create empty tick state with no cells
        RawTickState rawTickState = new RawTickState(1L, List.of(), List.of());
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act
        Molecule molecule = reader.getMolecule(new int[]{0, 0});
        
        // Assert
        assertThat(molecule).isNull();
    }

    @Test
    @Tag("unit")
    void getShape_returnsCorrectWorldShape() {
        // Arrange
        int[] worldShape = {25, 50};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, true);
        
        RawTickState rawTickState = new RawTickState(1L, List.of(), List.of());
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act
        int[] shape = reader.getShape();
        
        // Assert
        assertThat(shape).isEqualTo(worldShape);
        assertThat(shape).hasSize(2);
        assertThat(shape[0]).isEqualTo(25);
        assertThat(shape[1]).isEqualTo(50);
    }

    @Test
    @Tag("unit")
    void getProperties_returnsCorrectProperties() {
        // Arrange
        int[] worldShape = {15, 15};
        boolean isToroidal = false;
        EnvironmentProperties props = new EnvironmentProperties(worldShape, isToroidal);
        
        RawTickState rawTickState = new RawTickState(1L, List.of(), List.of());
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act
        EnvironmentProperties returnedProps = reader.getProperties();
        
        // Assert
        assertThat(returnedProps).isSameAs(props);
        assertThat(returnedProps.getWorldShape()).isEqualTo(worldShape);
        assertThat(returnedProps.isToroidal()).isEqualTo(isToroidal);
    }

    @Test
    @Tag("unit")
    void coordinateCalculation_withToroidalWorld() {
        // Arrange
        int[] worldShape = {10, 10};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, true); // Toroidal
        RawTickState rawTickState = new RawTickState(1L, List.of(), List.of());
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act & Assert: Test toroidal wrapping
        int[] nextPos = reader.getProperties().getNextPosition(new int[]{9, 5}, new int[]{1, 0});
        assertThat(nextPos).isEqualTo(new int[]{0, 5}); // Wraps around X-axis
        
        int[] nextPos2 = reader.getProperties().getNextPosition(new int[]{5, 9}, new int[]{0, 1});
        assertThat(nextPos2).isEqualTo(new int[]{5, 0}); // Wraps around Y-axis
    }

    @Test
    @Tag("unit")
    void coordinateCalculation_withNonToroidalWorld() {
        // Arrange
        int[] worldShape = {10, 10};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, false); // Non-toroidal
        RawTickState rawTickState = new RawTickState(1L, List.of(), List.of());
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act & Assert: Test non-toroidal behavior
        int[] nextPos = reader.getProperties().getNextPosition(new int[]{9, 5}, new int[]{1, 0});
        assertThat(nextPos).isEqualTo(new int[]{10, 5}); // Goes beyond boundary
        
        int[] nextPos2 = reader.getProperties().getNextPosition(new int[]{5, 9}, new int[]{0, 1});
        assertThat(nextPos2).isEqualTo(new int[]{5, 10}); // Goes beyond boundary
    }

    @Test
    @Tag("unit")
    void performance_largeNumberOfCells() {
        // Arrange: Create a large number of cells
        int[] worldShape = {100, 100};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, true);
        
        // Create 1000 random cells
        List<RawCellState> cells = new java.util.ArrayList<>();
        java.util.Random random = new java.util.Random(42); // Fixed seed for reproducibility
        
        for (int i = 0; i < 1000; i++) {
            int x = random.nextInt(100);
            int y = random.nextInt(100);
            int value = random.nextInt(1000);
            int type = random.nextInt(10);
            cells.add(new RawCellState(new int[]{x, y}, value, type));
        }
        
        RawTickState rawTickState = new RawTickState(1L, List.of(), cells);
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act: Measure access time
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 1000; i++) {
            int x = random.nextInt(100);
            int y = random.nextInt(100);
            reader.getMolecule(new int[]{x, y});
        }
        
        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        
        // Assert: Should be reasonably fast (less than 10ms for 1000 accesses)
        assertThat(totalTime).isLessThan(10_000_000); // 10ms in nanoseconds
        
        System.out.println("Performance test: 1000 random cell accesses in " + 
                          (totalTime / 1_000_000.0) + " ms");
    }

    @Test
    @Tag("unit")
    void edgeCases_boundaryCoordinates() {
        // Arrange
        int[] worldShape = {5, 5};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, true);
        
        // Create cells at boundaries
        RawCellState boundaryCell1 = new RawCellState(new int[]{0, 0}, 1, 0);
        RawCellState boundaryCell2 = new RawCellState(new int[]{4, 4}, 2, 0);
        RawCellState boundaryCell3 = new RawCellState(new int[]{0, 4}, 3, 0);
        RawCellState boundaryCell4 = new RawCellState(new int[]{4, 0}, 4, 0);
        
        List<RawCellState> cells = Arrays.asList(boundaryCell1, boundaryCell2, boundaryCell3, boundaryCell4);
        RawTickState rawTickState = new RawTickState(1L, List.of(), cells);
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act & Assert: Test boundary access
        assertThat(reader.getMolecule(new int[]{0, 0})).isNotNull();
        assertThat(reader.getMolecule(new int[]{4, 4})).isNotNull();
        assertThat(reader.getMolecule(new int[]{0, 4})).isNotNull();
        assertThat(reader.getMolecule(new int[]{4, 0})).isNotNull();
        
        // Test just outside boundaries
        assertThat(reader.getMolecule(new int[]{-1, 0})).isNull();
        assertThat(reader.getMolecule(new int[]{5, 0})).isNull();
        assertThat(reader.getMolecule(new int[]{0, -1})).isNull();
        assertThat(reader.getMolecule(new int[]{0, 5})).isNull();
    }

    @Test
    @Tag("unit")
    void moleculeTypeHandling_differentTypes() {
        // Arrange
        int[] worldShape = {10, 10};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, true);
        
        // Create cells with different types
        RawCellState cell1 = new RawCellState(new int[]{1, 1}, 42, 0);
        RawCellState cell2 = new RawCellState(new int[]{2, 2}, 100, 1);
        RawCellState cell3 = new RawCellState(new int[]{3, 3}, 200, 2);
        
        List<RawCellState> cells = Arrays.asList(cell1, cell2, cell3);
        RawTickState rawTickState = new RawTickState(1L, List.of(), cells);
        RawTickStateReader reader = new RawTickStateReader(rawTickState, props);
        
        // Act & Assert: Test different molecule types
        Molecule mol1 = reader.getMolecule(new int[]{1, 1});
        assertThat(mol1.type()).isEqualTo(0);
        
        Molecule mol2 = reader.getMolecule(new int[]{2, 2});
        assertThat(mol2.type()).isEqualTo(1);
        
        Molecule mol3 = reader.getMolecule(new int[]{3, 3});
        assertThat(mol3.type()).isEqualTo(2);
    }
}
