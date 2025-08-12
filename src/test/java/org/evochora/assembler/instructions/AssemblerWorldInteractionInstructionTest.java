package org.evochora.assembler.instructions;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Symbol;
import org.evochora.runtime.model.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AssemblerWorldInteractionInstructionTest {

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
        TestProgram program = new TestProgram(code);
        Map<int[], Integer> machineCode = program.assemble();

        for (Map.Entry<int[], Integer> entry : machineCode.entrySet()) {
            world.setSymbol(Symbol.fromInt(entry.getValue()), entry.getKey());
        }

        if (org == null) {
            org = Organism.create(sim, new int[]{0,0}, 2000, sim.getLogger());
        }
        sim.addOrganism(org);
        for(int i=0; i<cycles; i++) {
            sim.tick();
        }
        return org;
    }

    @Test
    void testPoke() {
        Organism org = Organism.create(sim, new int[]{0,0}, 2000, sim.getLogger());
        int[] vec = {0, 1}; // unit vector orthogonal to DIR
        int valueToPoke = new Symbol(Config.TYPE_DATA, 999).toInt();

        org.setDr(0, valueToPoke);
        org.setDr(1, vec);

        List<String> code = List.of("POKE %DR0 %DR1");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();

        int[] targetPos = new int[]{0, 1};
        assertThat(world.getSymbol(targetPos).toInt()).isEqualTo(valueToPoke);
        assertThat(res.getEr()).isLessThanOrEqualTo(2000 - 999 - 1);
    }

    @Test
    void testPoki() {
        Organism org = Organism.create(sim, new int[]{0,0}, 2000, sim.getLogger());
        int valueToPoke = new Symbol(Config.TYPE_DATA, 123).toInt();
        org.setDr(0, valueToPoke);

        // Use unit vector literal
        List<String> code = List.of("POKI %DR0 0|1");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();

        int[] target = new int[]{0, 1};
        assertThat(world.getSymbol(target).toInt()).isEqualTo(valueToPoke);
        assertThat(res.getEr()).isEqualTo(2000 - 123 - 1);
    }

    @Test
    void testPoks() {
        Organism org = Organism.create(sim, new int[]{0,0}, 2000, sim.getLogger());
        int payload = new Symbol(Config.TYPE_DATA, 55).toInt();
        int[] vec = new int[]{0, 1}; // unit vector
        // For POKS, operand 0 is value (top), operand 1 is vector (next). Push vector first, then value.
        org.getDataStack().push(vec);
        org.getDataStack().push(payload);

        List<String> code = List.of("POKS");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();

        int[] target = new int[]{0, 1};
        assertThat(world.getSymbol(target).toInt()).isEqualTo(payload);
        assertThat(res.getEr()).isEqualTo(2000 - 55 - 1);
    }

    @Test
    void testPeek() {
        Organism org = Organism.create(sim, new int[]{0,0}, 2000, sim.getLogger());
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] target = new int[]{0, 1};
        int payload = new Symbol(Config.TYPE_DATA, 7).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        org.setDr(1, vec);
        List<String> code = List.of("PEEK %DR0 %DR1");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(payload);
        // target cell should be cleared
        assertThat(world.getSymbol(target).isEmpty()).isTrue();
    }

    @Test
    void testPeki() {
        Organism org = Organism.create(sim, new int[]{0,0}, 2000, sim.getLogger());
        org.setDp(org.getIp());
        int[] target = new int[]{0, 1};
        int payload = new Symbol(Config.TYPE_DATA, 11).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        List<String> code = List.of("PEKI %DR0 0|1");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(payload);
        assertThat(world.getSymbol(target).isEmpty()).isTrue();
    }

    @Test
    void testPeks() {
        Organism org = Organism.create(sim, new int[]{0,0}, 2000, sim.getLogger());
        org.setDp(org.getIp());
        int[] vec = new int[]{-1, 0};
        int[] target = new int[]{-1, 0};
        int payload = new Symbol(Config.TYPE_DATA, 9).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        org.getDataStack().push(vec);
        List<String> code = List.of("PEKS");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDataStack().pop()).isEqualTo(payload);
        assertThat(world.getSymbol(target).isEmpty()).isTrue();
    }

    @Test
    void testPoke_TargetOccupied_NoOverwrite_EnergyCharged() {
        Organism org = Organism.create(sim, new int[]{0,0}, 2000, sim.getLogger());
        int[] vec = {0, 1}; // unit vector orthogonal to DIR
        int[] targetPos = new int[]{0, 1};
        int initialOccupant = new Symbol(Config.TYPE_DATA, 777).toInt();
        world.setSymbol(Symbol.fromInt(initialOccupant), targetPos);

        int valueToPoke = new Symbol(Config.TYPE_DATA, 42).toInt();
        org.setDr(0, valueToPoke);
        org.setDr(1, vec);

        List<String> code = List.of("POKE %DR0 %DR1");
        Organism res = runAssembly(code, org, 1);

        // Occupied target should yield a failure with a clear reason
        assertThat(res.isInstructionFailed()).as("Expected failure on occupied target").isTrue();
        assertThat(res.getFailureReason()).contains("Target cell is not empty.");

        // Cell unchanged
        assertThat(world.getSymbol(targetPos).toInt()).isEqualTo(initialOccupant);
        assertThat(res.getEr()).isLessThanOrEqualTo(2000 - 42 - 1);
    }
}
