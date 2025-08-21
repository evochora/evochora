package org.evochora.compiler.e2e;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.CallBindingRegistry;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class RuntimeIntegrationTest {

	@Test
	void procedureWithParametersAddsValuesAtRuntime() throws Exception {
		String source = String.join("\n",
				".PROC ADD2 EXPORT WITH A B",
				"  ADDR A B",
				"  RET",
				".ENDP",
				"SETI %DR0 DATA:1",
				"SETI %DR1 DATA:2",
				"CALL ADD2 WITH %DR0 %DR1",
				"NOP"
		);

		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "rt_proc_params.s");
		assertThat(artifact).isNotNull();

		// Create a small environment and simulation
		int width = 64, height = 64;
		Environment env = new Environment(new int[]{width, height}, true);
		Simulation sim = new Simulation(env);

		// Place program at origin (0,0)
		for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
			int[] rel = e.getKey();
			int[] abs = new int[]{rel[0], rel[1]};
			env.setMolecule(Molecule.fromInt(e.getValue()), abs);
		}

		Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
		org.setProgramId(artifact.programId());
		sim.addOrganism(org);

		// Run a few ticks to execute SETI, SETI, CALL, and within proc ADDR and RET
		for (int i = 0; i < 10; i++) sim.tick();

		// Basic runtime sanity checks
		assertThat(org.getDrs().size()).isGreaterThan(1);
		assertThat(org.getDrs().get(0)).isNotNull();
		assertThat(org.getDrs().get(1)).isNotNull();
	}

    /**
     * Testfall 1: Überprüft, ob das Copy-Out von Prozedur-Parametern
     * korrekt funktioniert, wenn zur Laufzeit KEIN ProgramArtifact verfügbar ist.
     * Dies stellt sicher, dass die Logik vollständig im Maschinencode enthalten ist.
     */
    @Test
    void procedureCopyOut_worksWithoutProgramArtifact() throws Exception {
        // Arrange: Eine Prozedur, die ihren Parameter inkrementiert
        String source = String.join("\n",
                ".PROC INCREMENT EXPORT WITH VALUE",
                "  ADDI VALUE DATA:1",
                "  RET",
                ".ENDP",
                "SETI %DR0 DATA:41",
                "CALL INCREMENT WITH %DR0", // Nach diesem Aufruf muss %DR0 den Wert 42 haben
                "NOP"
        );

        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "artifact_free_test.s");
        assertThat(artifact).isNotNull();

        // Setup der Simulation
        Environment env = new Environment(new int[]{64, 64}, true);
        Simulation sim = new Simulation(env);

        // Der entscheidende Schritt: Wir "löschen" das Artefakt zur Laufzeit,
        // indem wir der Simulation eine leere Map übergeben.
        sim.setProgramArtifacts(Collections.emptyMap());

        // Platziere den Code in der Welt
        for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
            env.setMolecule(Molecule.fromInt(e.getValue()), e.getKey());
        }

        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        // Wichtig: Die programId wird gesetzt, aber die Simulation kennt das zugehörige Artefakt nicht.
        org.setProgramId(artifact.programId());
        sim.addOrganism(org);

        // Act: Führe genug Ticks aus, damit die Prozedur abgeschlossen wird.
        for (int i = 0; i < 15; i++) {
            sim.tick();
        }

        // Assert: Überprüfe, ob der Wert in %DR0 korrekt zurückgeschrieben wurde.
        // Dies sollte nur funktionieren, wenn die POP-Befehle des Compilers die Arbeit machen.
        Molecule result = Molecule.fromInt((Integer) org.getDr(0));
        assertThat(result.toScalarValue())
                .as("Der Wert sollte nach dem Prozeduraufruf auf 42 inkrementiert sein, auch ohne Artefakt.")
                .isEqualTo(42);
        assertThat(org.isInstructionFailed()).isFalse();
    }

    @Test
    void procedureCall_worksCorrectlyWithCorruptedProgramArtifact() throws Exception {
        // Arrange: Ein einfaches Programm, das 10 + 20 addieren soll.
        String sourceCode = String.join("\n",
                ".PROC ADD EXPORT WITH A B",
                "  ADDR A B",
                "  RET",
                ".ENDP",
                "SETI %DR0 DATA:10",
                "SETI %DR1 DATA:20",
                "CALL ADD WITH %DR0 %DR1", // Ergebnis in %DR0 sollte 30 sein
                "NOP"
        );

        Compiler compiler = new Compiler();
        ProgramArtifact correctArtifact = compiler.compile(Arrays.asList(sourceCode.split("\\r?\\n")), "correct.s");
        assertThat(correctArtifact).isNotNull();

        // Erstelle ein absichtlich falsches/korruptes Artefakt.
        // Wir nehmen die Metadaten vom korrekten Artefakt, aber überschreiben die kritischen Bindungen.
        Map<Integer, int[]> corruptedBindings = new HashMap<>();
        // FALSCH: Wir tun so, als würde der CALL nur EINEN Parameter (%DR1) übergeben.
        // Wenn die Runtime dies liest, wird sie die Parameter falsch verarbeiten.
        correctArtifact.callSiteBindings().forEach((addr, bindings) -> {
            corruptedBindings.put(addr, new int[]{1}); // Nur %DR1 binden
        });

        ProgramArtifact corruptedArtifact = new ProgramArtifact(
                correctArtifact.programId(),
                correctArtifact.sources(),
                correctArtifact.machineCodeLayout(),
                correctArtifact.initialWorldObjects(),
                correctArtifact.sourceMap(),
                corruptedBindings, // Hier kommen die falschen Daten rein
                correctArtifact.relativeCoordToLinearAddress(),
                correctArtifact.linearAddressToCoord(),
                correctArtifact.labelAddressToName(),
                correctArtifact.registerAliasMap(),
                correctArtifact.procNameToParamNames()
        );

        // Setup der Simulation
        Environment env = new Environment(new int[]{64, 64}, true);
        Simulation sim = new Simulation(env);

        // WICHTIG: Wir laden den korrekten Maschinencode in die Welt...
        for (Map.Entry<int[], Integer> e : correctArtifact.machineCodeLayout().entrySet()) {
            env.setMolecule(Molecule.fromInt(e.getValue()), e.getKey());
        }

        // ...ABER wir füttern die Simulation mit dem korrupten Artefakt.
        sim.setProgramArtifacts(Map.of(corruptedArtifact.programId(), corruptedArtifact));

        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setProgramId(correctArtifact.programId());
        sim.addOrganism(org);

        // Fülle die Registry mit den korrupten Daten, so wie es die SimulationEngine tun würde.
        CallBindingRegistry.getInstance().clearAll(); // Wichtig: Registry vor dem Test säubern!
        for (var binding : corruptedArtifact.callSiteBindings().entrySet()) {
            int[] coord = corruptedArtifact.linearAddressToCoord().get(binding.getKey());
            if (coord != null) {
                CallBindingRegistry.getInstance().registerBindingForAbsoluteCoord(coord, binding.getValue());
            }
        }

        // Act: Führe die Simulation aus.
        for (int i = 0; i < 20; i++) {
            sim.tick();
        }

        // Assert: Das Ergebnis muss dem Maschinencode entsprechen (10 + 20 = 30).
        // Wenn die Runtime das korrupte Artefakt gelesen hätte, wäre das Ergebnis falsch
        // (wahrscheinlich 20 + 20 = 40, da %DR0 nie korrekt übergeben worden wäre).
        Molecule result = Molecule.fromInt((Integer) org.getDr(0));
        assertThat(result.toScalarValue())
                .as("Das Ergebnis der Addition muss 30 sein, basierend auf dem Maschinencode, nicht dem falschen Artefakt.")
                .isEqualTo(30);
        assertThat(org.isInstructionFailed()).isFalse();
    }
}


