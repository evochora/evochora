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

public class AssemblerStateInstructionTest {

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
    private final int[] startPos = new int[]{0, 0};

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
            org = Organism.create(sim, startPos, 1000, sim.getLogger());
        }
        sim.addOrganism(org);
        for(int i=0; i<cycles; i++) {
            sim.tick();
        }
        // Ensure no instruction failed during this run
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        return org;
    }

    @Test
    void testTurn() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        int[] vec = new int[]{1, 0};
        org.setDr(0, vec);

        List<String> code = List.of("TURN %DR0");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.getDv()).isEqualTo(vec);
    }

    @Test
    void testSync() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        int[] expected = org.getIp();

        List<String> code = List.of("SYNC");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.getDp()).isEqualTo(expected);
    }

    @Test
    void testNrg() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());

        List<String> code = List.of("NRG %DR0");
        Organism res = runAssembly(code, org, 1);

        int er = res.getEr();
        int regVal = (Integer) res.getDr(0);
        assertThat(Symbol.fromInt(regVal).toScalarValue()).isEqualTo(er);
    }

    @Test
    void testNrgs() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());

        List<String> code = List.of("NRGS");
        Organism res = runAssembly(code, org, 1);

        int val = (Integer) res.getDataStack().pop();
        assertThat(Symbol.fromInt(val).toScalarValue()).isEqualTo(res.getEr());
    }

    @Test
    void testDiff() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        org.setDp(org.getIp());

        List<String> code = List.of("DIFF %DR0");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.getDr(0)).isEqualTo(new int[]{0, 0});
    }

    @Test
    void testPos() {
        List<String> code = List.of("POS %DR0");
        Organism finalOrg = runAssembly(code, null, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new int[]{0,0});
    }

    @Test
    void testRand() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        org.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());

        List<String> code = List.of("RAND %DR0");
        Organism res = runAssembly(code, org, 1);

        int val = Symbol.fromInt((Integer) res.getDr(0)).toScalarValue();
        assertThat(val).isGreaterThanOrEqualTo(0).isLessThan(10);
    }

    @Test
    void testSeek() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        org.setDr(0, vec);
        int[] expected = org.getTargetCoordinate(org.getDp(), vec, world);

        List<String> code = List.of("SEEK %DR0");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.getDp()).isEqualTo(expected);
    }

    @Test
    void testSeki() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] expected = org.getTargetCoordinate(org.getDp(), vec, world);

        List<String> code = List.of("SEKI 0|1");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.getDp()).isEqualTo(expected);
    }

    @Test
    void testSeks() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        org.setDp(org.getIp());
        int[] vec = new int[]{-1, 0};
        int[] expected = org.getTargetCoordinate(org.getDp(), vec, world);
        org.getDataStack().push(vec);

        List<String> code = List.of("SEKS");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.getDp()).isEqualTo(expected);
    }

    @Test
    void testScan() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Symbol(Config.TYPE_STRUCTURE, 3).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        org.setDr(1, vec);
        List<String> code = List.of("SCAN %DR0 %DR1");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.getDr(0)).isEqualTo(payload);
        // cell not consumed
        assertThat(world.getSymbol(target).toInt()).isEqualTo(payload);
    }

    @Test
    void testScni() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Symbol(Config.TYPE_CODE, 42).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        List<String> code = List.of("SCNI %DR0 0|1");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.getDr(0)).isEqualTo(payload);
        assertThat(world.getSymbol(target).toInt()).isEqualTo(payload);
    }

    @Test
    void testScns() {
        Organism org = Organism.create(sim, startPos, 1000, sim.getLogger());
        org.setDp(org.getIp());
        int[] vec = new int[]{-1, 0};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Symbol(Config.TYPE_ENERGY, 5).toInt();
        world.setSymbol(Symbol.fromInt(payload), target);

        org.getDataStack().push(vec);
        List<String> code = List.of("SCNS");
        Organism res = runAssembly(code, org, 1);

        assertThat(res.getDataStack().pop()).isEqualTo(payload);
        assertThat(world.getSymbol(target).toInt()).isEqualTo(payload);
    }
}
