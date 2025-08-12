package org.evochora.assembler.prototypes;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblerException;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class TestBase {

    protected World world;
    protected Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{100, 100}, true);
        sim = new Simulation(world);
    }

    protected Organism runTest(String code, int ticks) {
        AssemblyProgram program = new AssemblyProgram("Test.s") {
            @Override
            public String getProgramCode() { return code; }
        };

        try {
            Map<int[], Integer> machineCode = program.assemble();

            // Dieser Teil wird nur bei erfolgreicher Assemblierung ausgeführt
            Map<int[], Molecule> initialObjects = program.getInitialWorldObjects();
            for (Map.Entry<int[], Molecule> entry : initialObjects.entrySet()) {
                world.setMolecule(entry.getValue(), entry.getKey());
            }

            Organism org = Organism.create(sim, program.getProgramOrigin(), 2000, sim.getLogger());
            sim.addOrganism(org);

            for (int i = 0; i < ticks; i++) {
                sim.tick();
            }

            assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
            return org;

        } catch (AssemblerException e) {
            // HIER IST DIE LÖSUNG:
            // 1. Wir geben die detaillierte, formatierte Fehlermeldung auf der Konsole aus.
            System.err.println(e.getMessage());

            // 2. Wir werfen die Exception erneut, damit JUnit den Test korrekt als FAILED markiert.
            throw e;
        }
    }

    protected int toData(int value) {
        return new Molecule(Config.TYPE_DATA, value).toInt();
    }
}