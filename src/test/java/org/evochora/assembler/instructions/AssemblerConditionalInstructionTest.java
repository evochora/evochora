package org.evochora.assembler.instructions;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AssemblerConditionalInstructionTest {

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

    private Environment environment;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);
        sim = new Simulation(environment);
        // Create a dummy organism to ensure the main test organism does not have ID 0
        Organism.create(sim, new int[]{-1, -1}, 1, sim.getLogger());
    }

    private Organism runAssembly(List<String> code, Organism org, int cycles) {
        TestProgram program = new TestProgram(code);
        Map<int[], Integer> machineCode = program.assemble();

        for (Map.Entry<int[], Integer> entry : machineCode.entrySet()) {
            environment.setMolecule(Molecule.fromInt(entry.getValue()), entry.getKey());
        }

        if (org == null) {
            org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        }
        sim.addOrganism(org);
        int ticks = Math.max(2, cycles); // ensure instruction after IF* gets a tick
        for (int i = 0; i < ticks; i++) {
            sim.tick();
        }
        // Ensure no instruction failed during this run
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        return org;
    }

    // IFR: True then False
    @Test
    void testIfr_True() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 5).toInt());
        List<String> code = List.of("IFR %DR0 %DR1", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 6).toInt());
    }

    @Test
    void testIfr_False() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        List<String> code = List.of("IFR %DR0 %DR1", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 5).toInt());
    }

    // IFI: True then False
    @Test
    void testIfi_True() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        List<String> code = List.of("IFI %DR0 DATA:10", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 11).toInt());
    }

    @Test
    void testIfi_False() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        List<String> code = List.of("IFI %DR0 DATA:5", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 10).toInt());
    }

    // IFS: True then False
    @Test
    void testIfs_True() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        // Ensure DR0 has DATA type so ADDI writes a DATA: value
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 7).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 7).toInt());
        List<String> code = List.of("IFS", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testIfs_False() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 7).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 8).toInt());
        List<String> code = List.of("IFS", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
    }

    // LTR/LTI/LTS
    @Test
    void testLtr_True_then_False() {
        // True
        Organism orgT = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgT.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        orgT.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        Organism resT = runAssembly(List.of("LTR %DR0 %DR1", "ADDI %DR0 DATA:1"), orgT, 2);
        assertThat(resT.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        // False
        Organism orgF = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgF.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        orgF.setDr(1, new Molecule(Config.TYPE_DATA, 1).toInt());
        Organism resF = runAssembly(List.of("LTR %DR0 %DR1", "ADDI %DR0 DATA:1"), orgF, 2);
        assertThat(resF.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
    }

    @Test
    void testLti_True_then_False() {
        Organism orgT = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgT.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        Organism resT = runAssembly(List.of("LTI %DR0 DATA:2", "ADDI %DR0 DATA:1"), orgT, 2);
        assertThat(resT.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());

        Organism orgF = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgF.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        Organism resF = runAssembly(List.of("LTI %DR0 DATA:1", "ADDI %DR0 DATA:1"), orgF, 1);
        assertThat(resF.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
    }

    @Test
    void testLts_True_then_False() {
        // True: top=1, second=2
        Organism orgT = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgT.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        orgT.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        orgT.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        Organism resT = runAssembly(List.of("LTS", "ADDI %DR0 DATA:1"), orgT, 1);
        assertThat(resT.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        // False: top=2, second=1
        Organism orgF = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgF.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        orgF.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        orgF.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        Organism resF = runAssembly(List.of("LTS", "ADDI %DR0 DATA:1"), orgF, 1);
        assertThat(resF.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
    }

    // GTR/GTI/GTS
    @Test
    void testGtr_True_then_False() {
        Organism orgT = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgT.setDr(0, new Molecule(Config.TYPE_DATA, 3).toInt());
        orgT.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        Organism resT = runAssembly(List.of("GTR %DR0 %DR1", "ADDI %DR0 DATA:1"), orgT, 1);
        assertThat(resT.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 4).toInt());

        Organism orgF = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgF.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        orgF.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        Organism resF = runAssembly(List.of("GTR %DR0 %DR1", "ADDI %DR0 DATA:1"), orgF, 1);
        assertThat(resF.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testGti_True_then_False() {
        Organism orgT = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgT.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        Organism resT = runAssembly(List.of("GTI %DR0 DATA:1", "ADDI %DR0 DATA:1"), orgT, 1);
        assertThat(resT.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 3).toInt());

        Organism orgF = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgF.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        Organism resF = runAssembly(List.of("GTI %DR0 DATA:2", "ADDI %DR0 DATA:1"), orgF, 1);
        assertThat(resF.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testGts_True_then_False() {
        // True: top=2, second=1
        Organism orgT = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgT.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        orgT.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        orgT.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        Organism resT = runAssembly(List.of("GTS", "ADDI %DR0 DATA:1"), orgT, 1);
        assertThat(resT.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        // False: top=1, second=2
        Organism orgF = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgF.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        orgF.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        orgF.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        Organism resF = runAssembly(List.of("GTS", "ADDI %DR0 DATA:1"), orgF, 1);
        assertThat(resF.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
    }

    // IFTR/IFTI/IFTS (type equality)
    @Test
    void testIftr_True_then_False() {
        // True (both DATA)
        Organism orgT = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgT.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        orgT.setDr(1, new Molecule(Config.TYPE_DATA, 9).toInt());
        Organism resT = runAssembly(List.of("IFTR %DR0 %DR1", "ADDI %DR0 DATA:1"), orgT, 1);
        assertThat(resT.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 8).toInt());

        // False (DATA vs CODE)
        Organism orgF = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgF.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        orgF.setDr(1, new Molecule(Config.TYPE_CODE, 9).toInt());
        Organism resF = runAssembly(List.of("IFTR %DR0 %DR1", "ADDI %DR0 DATA:1"), orgF, 1);
        assertThat(resF.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    void testIfti_True_then_False() {
        // True (DATA vs DATA)
        Organism orgT = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgT.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        Organism resT = runAssembly(List.of("IFTI %DR0 DATA:123", "ADDI %DR0 DATA:1"), orgT, 1);
        assertThat(resT.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());

        // False (CODE vs DATA)
        Organism orgF = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgF.setDr(0, new Molecule(Config.TYPE_CODE, 0).toInt());
        Organism resF = runAssembly(List.of("IFTI %DR0 DATA:123", "ADDI %DR0 DATA:1"), orgF, 1);
        assertThat(resF.getDr(0)).isEqualTo(new Molecule(Config.TYPE_CODE, 0).toInt());
    }

    @Test
    void testIfts_True_then_False() {
        // True: both DATA-typed
        Organism orgT = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgT.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        orgT.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        orgT.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        Organism resT = runAssembly(List.of("IFTS", "ADDI %DR0 DATA:1"), orgT, 1);
        assertThat(resT.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());

        // False: mixed types
        Organism orgF = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        orgF.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        orgF.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        orgF.getDataStack().push(new Molecule(Config.TYPE_CODE, 2).toInt());
        Organism resF = runAssembly(List.of("IFTS", "ADDI %DR0 DATA:1"), orgF, 1);
        assertThat(resF.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
    }

    // IFMR/IFMI/IFMS
    @Test
    void testIfmr_NotOwned_Skips() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(1, new int[]{0, 1}); // unit vector
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        // Cell at [0, 1] is unowned (ownerId=0), org.getId() is >= 1.
        List<String> code = List.of("IFMR %DR1", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 5).toInt());
    }

    @Test
    void testIfmr_Owned_Executes() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(1, new int[]{0, 1}); // unit vector
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        environment.setOwnerId(org.getId(), 0, 1); // Set owner to current organism
        List<String> code = List.of("IFMR %DR1", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 6).toInt());
    }

    @Test
    void testIfmi_NotOwned_Skips() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        // Cell at [0, 1] is unowned (ownerId=0), org.getId() is >= 1.
        List<String> code = List.of("IFMI 0|1", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 5).toInt());
    }

    @Test
    void testIfmi_Owned_Executes() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        environment.setOwnerId(org.getId(), 0, 1); // Set owner to current organism
        List<String> code = List.of("IFMI 0|1", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 6).toInt());
    }

    @Test
    void testIfms_NotOwned_Skips() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        org.getDataStack().push(new int[]{0, 1});
        // Cell at [0, 1] is unowned (ownerId=0), org.getId() is >= 1.
        List<String> code = List.of("IFMS", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 5).toInt());
    }

    @Test
    void testIfms_Owned_Executes() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        org.getDataStack().push(new int[]{0, 1});
        environment.setOwnerId(org.getId(), 0, 1); // Set owner to current organism
        List<String> code = List.of("IFMS", "ADDI %DR0 DATA:1");
        Organism finalOrg = runAssembly(code, org, 2);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 6).toInt());
    }
}