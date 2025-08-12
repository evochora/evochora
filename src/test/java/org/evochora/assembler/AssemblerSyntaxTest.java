package org.evochora.assembler;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AssemblerSyntaxTest {

    // KORRIGIERT: Diese Helferklasse wird nicht mehr benötigt, da wir die
    // Compiler-API direkt verwenden.
    // private static class TestProgram extends AssemblyProgram { ... }

    private World world;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{50, 50}, true);
        sim = new Simulation(world);
    }

    private Organism runAssembly(List<String> code, Organism org, int cycles) {
        // Verwende die neue Compiler-API
        org.evochora.compiler.api.Compiler compiler = new org.evochora.compiler.internal.LegacyCompilerAdapter();
        try {
            org.evochora.compiler.api.ProgramArtifact artifact = compiler.compile(code, "SyntaxTest.s");

            // Platziere den Maschinencode in der Welt
            for (Map.Entry<int[], Integer> entry : artifact.machineCodeLayout().entrySet()) {
                world.setMolecule(Molecule.fromInt(entry.getValue()), entry.getKey());
            }

            if (org == null) {
                org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
            }

            // TODO: [Phase 3] In der Zukunft wird der Organismus direkt mit dem Artefakt
            //  verbunden, nicht mehr über die AssemblyProgram-Registry.
            //  Für jetzt müssen wir das alte System noch füttern.
            AssemblyProgram.registerProgram(artifact, org);

            sim.addOrganism(org);

        } catch (org.evochora.compiler.api.CompilationException e) {
            throw new RuntimeException("Test setup failed due to compilation error", e);
        }
        for (int i = 0; i < cycles; i++) sim.tick();
        return org;
    }

    @Test
    void testImportTrampolineCallsProc() {
        List<String> code = List.of(
            ".PROC INC WITH A",
            "  ADDI A DATA:1",
            "  RET",
            ".ENDP",
            ".IMPORT INC AS P",
            "CALL P .WITH %DR0"
        );
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());

        Organism res = runAssembly(code, org, 5);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testRoutineTwoParamsInclude() {
        List<String> code = List.of(
            ".ROUTINE BUMP X Y",
            "  ADDI X DATA:1",
            "  ADDI Y DATA:2",
            ".ENDR",
            ".INCLUDE SYNTAXTEST.BUMP AS B1 WITH %DR0 %DR1"
        );
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0).toInt());

        Organism res = runAssembly(code, org, 2);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertThat(res.getDr(1)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
    }

    @Test
    void testMacroInsideRoutine() {
        List<String> code = List.of(
            ".MACRO $INC ARG",
            "  ADDI ARG DATA:1",
            ".ENDM",
            ".ROUTINE R ARG",
            "  $INC ARG",
            ".ENDR",
            ".INCLUDE SYNTAXTEST.R AS R0 WITH %DR0"
        );
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());

        Organism res = runAssembly(code, org, 1);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 6).toInt());
    }

    // --- Additional positive syntax coverage ---

    @Test
    void testLabelAndJmpi() {
        List<String> code = List.of(
            "SETI %DR0 DATA:0",
            "L1:",
            "ADDI %DR0 DATA:1",
            "JMPI L1"
        );
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        Organism res = runAssembly(code, org, 4);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        // SETI -> ADDI -> JMPI (back to L1) -> ADDI again => 2
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
    }

    @Test
    void testForwardReferencedLabel() {
        List<String> code = List.of(
            "JMPI TARGET",
            "SETI %DR0 DATA:99",
            "TARGET:",
            "ADDI %DR0 DATA:1"
        );
        // Ensure DR0 is DATA-typed before ADDI so result is DATA:1
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        Organism res = runAssembly(code, org, 2);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testLabelCaseInsensitiveReference() {
        List<String> code = List.of(
            "SETI %DR0 DATA:0",
            "Loop:",
            "ADDI %DR0 DATA:1",
            "JMPI LOOP"
        );
        Organism res = runAssembly(code, null, 4);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
    }

    @Test
    void testCommentsIgnored() {
        List<String> code = List.of(
            ".ORG 0|0   # origin here",
            "SETI %DR0 DATA:1   # increment",
            "# whole-line comment",
            "    # leading spaces then comment",
            "ADDI %DR0 DATA:2   # add two"
        );
        Organism res = runAssembly(code, null, 2);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 3).toInt());
    }

    @Test
    void testWhitespaceTolerance() {
        List<String> code = List.of(
            " \t.DIR\t0|1   ",
            " \tSETI  \t%DR0   \tDATA:2  ",
            "ADDI    %DR0\tDATA:3   "
        );
        // Ensure the organism fetches along Y to follow the .DIR layout
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDv(new int[]{0, 1});
        Organism res = runAssembly(code, org, 2);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 5).toInt());
    }
}
