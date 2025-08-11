package org.evochora.assembler.prototypes;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerException;
import org.evochora.assembler.AssemblyProgram;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
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
            Map<int[], Symbol> initialObjects = program.getInitialWorldObjects();
            for (Map.Entry<int[], Symbol> entry : initialObjects.entrySet()) {
                world.setSymbol(entry.getValue(), entry.getKey());
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
            System.err.println(e.getFormattedMessage());

            // 2. Wir werfen die Exception erneut, damit JUnit den Test korrekt als FAILED markiert.
            throw e;
        }
    }

    protected int toData(int value) {
        return new Symbol(Config.TYPE_DATA, value).toInt();
    }
}