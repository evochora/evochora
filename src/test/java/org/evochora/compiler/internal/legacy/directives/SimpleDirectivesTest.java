package org.evochora.compiler.internal.legacy.directives;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Symbol;
import org.evochora.runtime.model.World;
import org.evochora.compiler.internal.legacy.AssemblerException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SimpleDirectivesTest {

    private static class TestProgram extends AssemblyProgram {
        private final String code;
        public TestProgram(List<String> codeLines) {
            super("TestProgram.s");
            this.code = String.join("\n", codeLines);
        }
        @Override
        public String getProgramCode() {
            return code;
        }
    }

    private World world;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{100, 100}, true);
        sim = new Simulation(world);
    }

    private Organism runAssembly(List<String> code, Organism org, int cycles) {
        try{
            TestProgram program = new TestProgram(code);
            Map<int[], Integer> machineCode = program.assemble();

            int[] startPos = program.getProgramOrigin();
            for (Map.Entry<int[], Integer> entry : machineCode.entrySet()) {
                world.setSymbol(Symbol.fromInt(entry.getValue()), entry.getKey());
            }
            // Also place initial world objects emitted by directives like .PLACE
            Map<int[], Symbol> initialObjects = program.getInitialWorldObjects();
            for (Map.Entry<int[], Symbol> entry : initialObjects.entrySet()) {
                world.setSymbol(entry.getValue(), entry.getKey());
            }

            if (org == null) {
                org = Organism.create(sim, startPos, 1000, sim.getLogger());
            }
            sim.addOrganism(org);

            for(int i=0; i<cycles; i++) {
                sim.tick();
            }
            return org;
        } catch (AssemblerException e) {
            // 1. Gib die formatierte, detaillierte Fehlermeldung aus.
            System.err.println(e.getMessage());
            // 2. Wirf die Exception erneut, damit JUnit den Test korrekt als FEHLGESCHLAGEN markiert.
            throw e;
        }
    }

    @Test
    void testDefine() {
        List<String> code = List.of(
            ".DEFINE MY_VAL DATA:5",
            "SETI %DR0 MY_VAL"
        );
        Organism finalOrg = runAssembly(code, null, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 5).toInt());
    }

    @Test
    void testReg() {
        List<String> code = List.of(
                ".REG %X 0",
                "SETI %X DATA:123"
        );
        Organism finalOrg = runAssembly(code, null, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 123).toInt());
    }

    @Test
    void testOrg() {
        List<String> code = List.of(
                ".ORG 7|9",
                "SETI %DR0 DATA:1"
        );
        // Assemble and load program into world (no execution)
        runAssembly(code, null, 0);
        // Verify that the opcode at the origin matches SETI
        int[] origin = new int[]{7, 9};
        int setiOpcode = Instruction.getInstructionIdByName("SETI");
        assertThat(world.getSymbol(origin).toInt()).isEqualTo(new Symbol(Config.TYPE_CODE, setiOpcode).toInt());
    }

    @Test
    void testDir() {
        // Lay out code along Y and fetch along Y by setting organism DV to 0|1
        List<String> code = List.of(
                ".DIR 0|1",
                "SETI %DR0 DATA:1",
                "ADDI %DR0 DATA:2"
        );
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDv(new int[]{0, 1});
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 3).toInt());
    }

    @Test
    void testPlace() {
        // Place a DATA value and verify the world content
        List<String> code = List.of(
                ".PLACE DATA:5 3|4",
                ".PLACE STRUCTURE:9 10|1",
                "NOP"
        );
        runAssembly(code, null, 0);
        assertThat(world.getSymbol(new int[]{3, 4}).toInt()).isEqualTo(new Symbol(Config.TYPE_DATA, 5).toInt());
        assertThat(world.getSymbol(new int[]{10, 1}).toInt()).isEqualTo(new Symbol(Config.TYPE_STRUCTURE, 9).toInt());
    }

    @Test
    void testFileDirective_Success() {
        // Testet, ob das Laden einer Bibliothek über den korrekten Pfad funktioniert.
        List<String> code = List.of(
                ".FILE \"lib/test_lib.s\"", // Korrekter Pfad relativ zum Prototypen-Verzeichnis
                "SETI %MY_LIB_REG DATA:999"
        );
        Organism finalOrg = runAssembly(code, null, 1);
        assertThat(finalOrg.getDr(5)).isEqualTo(new Symbol(Config.TYPE_DATA, 999).toInt());
    }

    @Test
    void testFileDirective_FileNotFound() {
        // Testet, ob eine aussagekräftige Fehlermeldung geworfen wird,
        // wenn die Datei nicht existiert.
        List<String> code = List.of(
                ".FILE \"non_existent_file.s\""
        );

        TestProgram program = new TestProgram(code);

        // Wir erwarten eine AssemblerException mit einer spezifischen Nachricht.
        assertThatThrownBy(program::assemble)
                .isInstanceOf(AssemblerException.class)
                .hasMessageContaining("Error loading library file: non_existent_file.s");
    }
}
