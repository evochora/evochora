package org.evochora.compiler.e2e;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
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

		Organism org = Organism.create(sim, new int[]{0, 0}, Config.INITIAL_ORGANISM_ENERGY, sim.getLogger());
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

        Organism org = Organism.create(sim, new int[]{0, 0}, Config.INITIAL_ORGANISM_ENERGY, sim.getLogger());
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

    /**
     * Testfall 2: Überprüft das korrekte Verhalten nach einer Replikation (FORK).
     * Ein Kind-Organismus muss die gleiche Prozedurlogik korrekt ausführen können,
     * ohne dass die Laufzeitumgebung auf das ProgramArtifact zugreift.
     */
    @Test
    void forkedChild_canExecuteProcedureWithoutProgramArtifact() throws Exception {
        // Arrange: Code, der eine Prozedur aufruft und sich dann repliziert.
        // Das Kind startet direkt vor dem Prozeduraufruf.
        String source = String.join("\n",
                // Register-Aliase für Klarheit
                ".REG %REG_CHILD_DP_VEC  2",
                ".REG %REG_CHILD_ENERGY 3",
                ".REG %REG_CHILD_DV_VEC  4",

                ".PROC DOUBLE EXPORT WITH VALUE",
                "  ADDR VALUE VALUE", // Verdoppelt den Wert
                "  RET",
                ".ENDP",

                "START:",
                "  SETI %DR1 DATA:10",
                "  CALL DOUBLE WITH %DR1", // Elter: %DR1 wird zu 20

                // Bereite die Argumente für FORK in Registern vor
                "  SETV %REG_CHILD_DP_VEC CHILD_START_POS", // Lade relativen Vektor zum Kind-Start
                "  SETI %REG_CHILD_ENERGY DATA:500",      // Lade Start-Energie für das Kind
                "  SETV %REG_CHILD_DV_VEC 1|0",           // Setze initialen DV für das Kind

                // Rufe FORK mit den Registern auf
                "  FORK %REG_CHILD_DP_VEC %REG_CHILD_ENERGY %REG_CHILD_DV_VEC",
                "  JMPI END",

                "CHILD_START_POS:",
                "  SETI %DR1 DATA:5",
                "  CALL DOUBLE WITH %DR1", // Kind: %DR1 wird zu 10
                "END:",
                "  NOP"
        );

        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "fork_artifact_free_test.s");
        assertThat(artifact).isNotNull();

        // Setup der Simulation OHNE Artefakt
        Environment env = new Environment(new int[]{128, 128}, true);
        Simulation sim = new Simulation(env);
        sim.setProgramArtifacts(Collections.emptyMap());

        // Platziere den Code in der Welt
        for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
            env.setMolecule(Molecule.fromInt(e.getValue()), e.getKey());
        }

        Organism parent = Organism.create(sim, new int[]{0, 0}, Config.INITIAL_ORGANISM_ENERGY, sim.getLogger());
        parent.setProgramId(artifact.programId());
        sim.addOrganism(parent);

        // Act: Lasse die Simulation lange genug laufen, damit beide Organismen ihren Code ausführen.
        for (int i = 0; i < 30; i++) {
            sim.tick();
        }

        // Assert
        assertThat(sim.getOrganisms()).hasSize(2);
        Organism child = sim.getOrganisms().get(1); // Das zweite Organismus ist das Kind

        // Überprüfe den Zustand des Elternteils
        Molecule parentResult = Molecule.fromInt((Integer) parent.getDr(1));
        assertThat(parentResult.toScalarValue())
                .as("Eltern-Organismus sollte seinen Wert auf 20 verdoppelt haben.")
                .isEqualTo(20);

        // Überprüfe den Zustand des Kindes
        Molecule childResult = Molecule.fromInt((Integer) child.getDr(1));
        assertThat(childResult.toScalarValue())
                .as("Kind-Organismus sollte seinen Wert auf 10 verdoppelt haben, ohne Zugriff auf das Artefakt.")
                .isEqualTo(10);

        assertThat(parent.isInstructionFailed()).isFalse();
        assertThat(child.isInstructionFailed()).isFalse();
    }
}


