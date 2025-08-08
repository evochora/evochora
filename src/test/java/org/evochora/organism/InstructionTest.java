package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.organism.instructions.AddiInstruction;
import org.evochora.organism.instructions.SubiInstruction;
import org.evochora.world.Symbol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InstructionTest {

    private Organism organism;
    private Simulation simulation;

    @BeforeEach
    void setUp() {
        organism = mock(Organism.class);
        simulation = mock(Simulation.class);
    }

    @ParameterizedTest
    @CsvSource({
            "10, 5, 15",
            "0, 0, 0",
            "-10, 5, -5"
    })
    void testAddiInstruction(int initialValue, int literal, int expectedValue) {
        // Given
        when(organism.getDr(0)).thenReturn(new Symbol(Config.TYPE_DATA, initialValue).toInt());
        AddiInstruction instruction = new AddiInstruction(organism, 0, new Symbol(Config.TYPE_DATA, literal).toInt(), 0);

        // When
        instruction.execute(simulation);

        // Then
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(organism).setDr(eq(0), captor.capture());
        assertThat(Symbol.fromInt(captor.getValue()).toScalarValue()).isEqualTo(expectedValue);
    }

    @ParameterizedTest
    @CsvSource({
            "10, 5, 5",
            "0, 0, 0",
            "-10, 5, -15"
    })
    void testSubiInstruction(int initialValue, int literal, int expectedValue) {
        // Given
        when(organism.getDr(0)).thenReturn(new Symbol(Config.TYPE_DATA, initialValue).toInt());
        SubiInstruction instruction = new SubiInstruction(organism, 0, new Symbol(Config.TYPE_DATA, literal).toInt(), 0);

        // When
        instruction.execute(simulation);

        // Then
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(organism).setDr(eq(0), captor.capture());
        assertThat(Symbol.fromInt(captor.getValue()).toScalarValue()).isEqualTo(expectedValue);
    }
}
