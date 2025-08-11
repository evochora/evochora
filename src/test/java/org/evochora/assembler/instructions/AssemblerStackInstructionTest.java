package org.evochora.assembler.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblyProgram;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AssemblerStackInstructionTest {

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
            org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        }
        sim.addOrganism(org);
        for (int i=0; i<cycles; i++) {
            sim.tick();
        }
        // Ensure no instruction failed during this run
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        return org;
    }

    @Test
    void testDup() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        int v = new Symbol(Config.TYPE_DATA, 123).toInt();
        org.getDataStack().push(v);
        List<String> code = List.of("DUP");
        Organism res = runAssembly(code, org, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(v);
        assertThat(res.getDataStack().pop()).isEqualTo(v);
    }

    @Test
    void testSwap() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        int a = new Symbol(Config.TYPE_DATA, 1).toInt();
        int b = new Symbol(Config.TYPE_DATA, 2).toInt();
        org.getDataStack().push(a);
        org.getDataStack().push(b);
        List<String> code = List.of("SWAP");
        Organism res = runAssembly(code, org, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(a);
        assertThat(res.getDataStack().pop()).isEqualTo(b);
    }

    @Test
    void testDrop() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        int a = new Symbol(Config.TYPE_DATA, 1).toInt();
        int b = new Symbol(Config.TYPE_DATA, 2).toInt();
        org.getDataStack().push(a);
        org.getDataStack().push(b);
        List<String> code = List.of("DROP");
        Organism res = runAssembly(code, org, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(a);
    }

    @Test
    void testRot() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        int a = new Symbol(Config.TYPE_DATA, 1).toInt();
        int b = new Symbol(Config.TYPE_DATA, 2).toInt();
        int c = new Symbol(Config.TYPE_DATA, 3).toInt();
        org.getDataStack().push(a);
        org.getDataStack().push(b);
        org.getDataStack().push(c);
        List<String> code = List.of("ROT");
        Organism res = runAssembly(code, org, 1);
        // After ROT (a b c) -> (b c a) bottom..top
        assertThat(res.getDataStack().pop()).isEqualTo(a);
        assertThat(res.getDataStack().pop()).isEqualTo(c);
        assertThat(res.getDataStack().pop()).isEqualTo(b);
    }
}
