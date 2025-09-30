package org.evochora.runtime.services;

import org.evochora.runtime.isa.IEnvironmentReader;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains comprehensive unit tests for the {@link Disassembler} class.
 * These tests verify that the disassembler can correctly read machine code from a mock environment
 * and translate it into a structured, human-readable format. It covers valid instructions,
 * various argument types, error conditions, and edge cases.
 * These are unit tests and do not require external resources.
 */
class DisassemblerTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * Verifies that the disassembler can correctly process a valid instruction with its arguments.
     * This is a unit test for the disassembler's core logic.
     */
    @Test
    @Tag("unit")
    void disassemble_validInstruction_withArguments() {
        // Arrange
        Disassembler disassembler = new Disassembler();
        MockEnvironmentReader mockReader = new MockEnvironmentReader();
        
        // Mock: SETI instruction (opcode 1) with 1 argument
        mockReader.setMoleculeAt(new int[]{0, 0}, new Molecule(1, 0)); // SETI opcode
        mockReader.setMoleculeAt(new int[]{1, 0}, new Molecule(42, 0)); // Argument
        
        // Act
        DisassemblyData result = disassembler.disassemble(mockReader, new int[]{0, 0});
        
        // Assert
        assertThat(result).isNotNull();
        assertThat(result.opcodeId()).isEqualTo(1);
        assertThat(result.opcodeName()).isEqualTo("SETI");
        assertThat(result.argValues()).hasSize(1);
        assertThat(result.argValues()[0]).isEqualTo(42);
        assertThat(result.argPositions()).hasDimensions(1, 2);
        assertThat(result.argPositions()[0]).isEqualTo(new int[]{1, 0});
    }

    /**
     * Verifies that the disassembler handles an unknown opcode gracefully,
     * returning a result with a special "UNKNOWN" name.
     * This is a unit test for the disassembler's error handling.
     */
    @Test
    @Tag("unit")
    void disassemble_unknownOpcode() {
        // Arrange
        Disassembler disassembler = new Disassembler();
        MockEnvironmentReader mockReader = new MockEnvironmentReader();
        
        // Mock: Unknown opcode (999)
        mockReader.setMoleculeAt(new int[]{0, 0}, new Molecule(999, 0));
        
        // Act
        DisassemblyData result = disassembler.disassemble(mockReader, new int[]{0, 0});
        
        // Assert
        assertThat(result).isNotNull();
        assertThat(result.opcodeId()).isEqualTo(999);
        assertThat(result.opcodeName()).startsWith("UNKNOWN_OP");
        assertThat(result.argValues()).isEmpty();
        assertThat(result.argPositions()).isEmpty();
    }

    /**
     * Verifies that the disassembler correctly processes a valid instruction that has no arguments.
     * This is a unit test for the disassembler's core logic.
     */
    @Test
    @Tag("unit")
    void disassemble_instructionWithoutArguments() {
        // Arrange
        Disassembler disassembler = new Disassembler();
        MockEnvironmentReader mockReader = new MockEnvironmentReader();
        
        // Mock: NOP instruction (opcode 0) - no arguments
        mockReader.setMoleculeAt(new int[]{0, 0}, new Molecule(0, 0));
        
        // Act
        DisassemblyData result = disassembler.disassemble(mockReader, new int[]{0, 0});
        
        // Assert
        assertThat(result).isNotNull();
        assertThat(result.opcodeId()).isEqualTo(0);
        assertThat(result.opcodeName()).isEqualTo("NOP");
        assertThat(result.argValues()).isEmpty();
        assertThat(result.argPositions()).isEmpty();
    }

    /**
     * Verifies that the disassembler handles an instruction that is missing its arguments in the environment.
     * This is a unit test for the disassembler's robustness.
     */
    @Test
    @Tag("unit")
    void disassemble_incompleteInstruction() {
        // Arrange
        Disassembler disassembler = new Disassembler();
        MockEnvironmentReader mockReader = new MockEnvironmentReader();
        
        // Mock: SETI instruction but missing argument
        mockReader.setMoleculeAt(new int[]{0, 0}, new Molecule(1, 0)); // SETI opcode
        // Argument missing - should handle gracefully
        
        // Act
        DisassemblyData result = disassembler.disassemble(mockReader, new int[]{0, 0});
        
        // Assert
        assertThat(result).isNotNull();
        assertThat(result.opcodeId()).isEqualTo(1);
        assertThat(result.opcodeName()).isEqualTo("SETI");
        assertThat(result.argValues()).isEmpty(); // No arguments
    }

    /**
     * Verifies that the disassembler returns null when attempting to disassemble an empty cell.
     * This is a unit test for the disassembler's handling of empty space.
     */
    @Test
    @Tag("unit")
    void disassemble_nullMolecule() {
        // Arrange
        Disassembler disassembler = new Disassembler();
        MockEnvironmentReader mockReader = new MockEnvironmentReader();
        
        // Mock: No molecule at position (null)
        // mockReader returns null for getMolecule
        
        // Act
        DisassemblyData result = disassembler.disassemble(mockReader, new int[]{0, 0});
        
        // Assert
        assertThat(result).isNull();
    }

    /**
     * Verifies that the disassembler works correctly with different world shapes and coordinate systems.
     * This is a unit test for the disassembler's coordinate handling.
     */
    @Test
    @Tag("unit")
    void disassemble_withDifferentWorldShapes() {
        // Arrange
        Disassembler disassembler = new Disassembler();
        
        // Test with different world shapes
        int[][] worldShapes = {{10, 10}, {100, 50}, {50, 100}};
        
        for (int[] worldShape : worldShapes) {
            MockEnvironmentReader mockReader = new MockEnvironmentReader(worldShape);
            
            // Mock: Simple instruction
            mockReader.setMoleculeAt(new int[]{0, 0}, new Molecule(0, 0)); // NOP
            mockReader.setMoleculeAt(new int[]{1, 0}, new Molecule(42, 0)); // Arg
            
            // Act
            DisassemblyData result = disassembler.disassemble(mockReader, new int[]{0, 0});
            
            // Assert
            assertThat(result).isNotNull();
            assertThat(result.opcodeName()).isEqualTo("NOP");
        }
    }

    /**
     * Verifies that the disassembler works correctly at various edge and corner coordinates.
     * This is a unit test for the disassembler's boundary condition handling.
     */
    @Test
    @Tag("unit")
    void disassemble_edgeCaseCoordinates() {
        // Arrange
        Disassembler disassembler = new Disassembler();
        MockEnvironmentReader mockReader = new MockEnvironmentReader(new int[]{100, 100});
        
        // Test edge cases: boundaries, negative coordinates
        int[][] testCoords = {
            {0, 0},           // Origin
            {99, 99},         // Max boundary
            {50, 50},         // Middle
            {0, 99},          // Edge
            {99, 0}           // Edge
        };
        
        for (int[] coords : testCoords) {
            mockReader.setMoleculeAt(coords, new Molecule(0, 0)); // NOP
            mockReader.setMoleculeAt(mockReader.getProperties().getNextPosition(coords, new int[]{1, 0}), new Molecule(42, 0));
            
            // Act
            DisassemblyData result = disassembler.disassemble(mockReader, coords);
            
            // Assert
            assertThat(result).isNotNull();
            assertThat(result.opcodeName()).isEqualTo("NOP");
        }
    }

    /**
     * A mock implementation of {@link IEnvironmentReader} for providing a controlled
     * environment for the Disassembler tests.
     */
    private static class MockEnvironmentReader implements IEnvironmentReader {
        private final EnvironmentProperties properties;
        private final java.util.Map<String, Molecule> molecules = new java.util.HashMap<>();
        
        public MockEnvironmentReader() {
            this(new int[]{100, 100});
        }
        
        public MockEnvironmentReader(int[] worldShape) {
            this.properties = new EnvironmentProperties(worldShape, true);
        }
        
        public void setMoleculeAt(int[] coordinates, Molecule molecule) {
            molecules.put(Arrays.toString(coordinates), molecule);
        }
        
        @Override
        public Molecule getMolecule(int[] coordinates) {
            return molecules.get(Arrays.toString(coordinates));
        }
        
        @Override
        public int[] getShape() {
            return properties.getWorldShape();
        }
        
        @Override
        public EnvironmentProperties getProperties() {
            return properties;
        }
    }
}
