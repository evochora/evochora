package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.organism.instructions.ArithmeticInstruction;
import org.evochora.organism.instructions.BitwiseInstruction;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class InstructionTest {

    private Organism organism;
    private Simulation simulation;
    private World world;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        world = new World(new int[]{10, 10}, true);
        simulation = new Simulation(world);
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
    }

    private void setupInstruction(String name, int regId, int literal) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, regId), 1, 0);
        world.setSymbol(Symbol.fromInt(literal), 2, 0);
    }

    @ParameterizedTest
    @CsvSource({
            "10, 5, 15",
            "0, 0, 0",
            "-10, 5, -5"
    })
    void testAddiInstruction(int initialValue, int literal, int expectedValue) {
        // Given
        organism.setDr(0, new Symbol(Config.TYPE_DATA, initialValue).toInt());
        setupInstruction("ADDI", 0, new Symbol(Config.TYPE_DATA, literal).toInt());

        // When
        Instruction instruction = organism.planTick(world);
        instruction.execute(simulation);

        // Then
        Object result = organism.getDr(0);
        assertTrue(result instanceof Integer);
        assertEquals(expectedValue, Symbol.fromInt((Integer)result).toScalarValue());
    }

    @ParameterizedTest
    @CsvSource({
            "10, 5, 5",
            "0, 0, 0",
            "-10, 5, -15"
    })
    void testSubiInstruction(int initialValue, int literal, int expectedValue) {
        // Given
        organism.setDr(0, new Symbol(Config.TYPE_DATA, initialValue).toInt());
        setupInstruction("SUBI", 0, new Symbol(Config.TYPE_DATA, literal).toInt());

        // When
        Instruction instruction = organism.planTick(world);
        instruction.execute(simulation);

        // Then
        Object result = organism.getDr(0);
        assertTrue(result instanceof Integer);
        assertEquals(expectedValue, Symbol.fromInt((Integer)result).toScalarValue());
    }

    // KORRIGIERT: Fügt den Test für eine Bitwise-Instruktion hinzu, um den Compiler-Fehler zu beheben.
    @ParameterizedTest
    @CsvSource({
            "12, 10, 8" // 1100 & 1010 = 1000
    })
    void testAndiInstruction(int initialValue, int literal, int expectedValue) {
        // Given
        organism.setDr(0, new Symbol(Config.TYPE_DATA, initialValue).toInt());
        setupInstruction("ANDI", 0, new Symbol(Config.TYPE_DATA, literal).toInt());

        // When
        Instruction instruction = organism.planTick(world);
        instruction.execute(simulation);

        // Then
        Object result = organism.getDr(0);
        assertTrue(result instanceof Integer);
        assertEquals(expectedValue, Symbol.fromInt((Integer)result).toScalarValue());
    }
}
