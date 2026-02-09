package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GeneDuplicationPlugin}.
 */
@Tag("unit")
class GeneDuplicationPluginTest {

    private Environment environment;

    /** The child organism that was just born. */
    private Organism child;

    @BeforeEach
    void setUp() {
        // 30x20 toroidal environment
        environment = new Environment(new int[]{30, 20}, true);

        // Minimal thermodynamic config
        String thermoConfigStr = """
            default {
              className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
              options {
                base-energy = 1
                base-entropy = 1
              }
            }
            overrides {
              instructions {}
              families {}
            }
            """;
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(
                ConfigFactory.parseString(thermoConfigStr));

        com.typesafe.config.Config organismConfig = ConfigFactory.parseMap(Map.of(
                "max-energy", 32767,
                "max-entropy", 8191,
                "error-penalty-cost", 10
        ));

        Simulation simulation = new Simulation(environment, policyManager, organismConfig);

        // Create parent organism (born at tick 0, no parent)
        Organism parent = Organism.create(simulation, new int[]{0, 0}, 10000, null);
        simulation.addOrganism(parent);

        // Create child organism
        child = Organism.restore(2, 9)
                .parentId(parent.getId())
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .energy(5000)
                .build(simulation);
        simulation.addOrganism(child);
    }

    /**
     * Places a row of molecules owned by the child at the given y coordinate.
     * Simulates a code row with a LABEL at position (labelX, y) and code elsewhere.
     */
    private void placeCodeRow(int y, int fromX, int toX, int labelX) {
        for (int x = fromX; x <= toX; x++) {
            Molecule mol;
            if (x == labelX) {
                mol = new Molecule(Config.TYPE_LABEL, 12345);
            } else {
                mol = new Molecule(Config.TYPE_CODE, 42); // some instruction
            }
            environment.setMolecule(mol, child.getId(), new int[]{x, y});
        }
    }

    /**
     * Places an empty row owned by the child (CODE:0 cells with ownership).
     * This simulates an empty code row between structure edges.
     */
    private void placeEmptyOwnedRow(int y, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), child.getId(), new int[]{x, y});
        }
    }

    @Test
    void duplicatesCodeBlockIntoNopArea() {
        // Code row at y=2: code from x=0..7 (with label at x=5), NOP area from x=8..14
        placeCodeRow(2, 0, 7, 5);
        placeEmptyOwnedRow(2, 8, 14); // NOP area on same row as code

        // Second scan line: entirely empty (another NOP target option)
        placeEmptyOwnedRow(4, 0, 14);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        // Verify that some non-empty molecules were copied to an NOP area
        // (either y=2 x=8..14 or y=4 x=0..14)
        int copiedCount = 0;
        for (int y : new int[]{2, 4}) {
            int startX = (y == 2) ? 8 : 0;
            int endX = 14;
            for (int x = startX; x <= endX; x++) {
                Molecule mol = environment.getMolecule(x, y);
                if (!mol.isEmpty()) {
                    // Verify owner is the child
                    assertThat(environment.getOwnerId(x, y)).isEqualTo(child.getId());
                    copiedCount++;
                }
            }
        }
        assertThat(copiedCount).as("At least some molecules should be copied into a NOP area").isGreaterThan(0);
    }

    @Test
    void skipsOrganismWithNoLabels() {
        // Code row WITHOUT any labels
        for (int x = 0; x <= 14; x++) {
            environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{x, 2});
        }
        placeEmptyOwnedRow(4, 0, 14);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        // Empty row should remain empty
        for (int x = 0; x <= 14; x++) {
            assertThat(environment.getMolecule(x, 4).value()).isEqualTo(0);
        }
    }

    @Test
    void skipsWhenNopAreaTooSmall() {
        // Code row with label
        placeCodeRow(2, 0, 14, 5);

        // Row with only 2 empty cells (less than minNopSize=5)
        for (int x = 0; x <= 14; x++) {
            if (x >= 7 && x <= 8) {
                environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), child.getId(), new int[]{x, 4});
            } else {
                environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), child.getId(), new int[]{x, 4});
            }
        }

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 5); // minNopSize=5
        plugin.onBirth(child, environment);

        // The two empty cells should still be empty
        assertThat(environment.getMolecule(7, 4).isEmpty()).isTrue();
        assertThat(environment.getMolecule(8, 4).isEmpty()).isTrue();
    }

    @Test
    void zeroDuplicationRateNeverDuplicates() {
        placeCodeRow(2, 0, 14, 5);
        placeEmptyOwnedRow(4, 0, 14);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 0.0, 3); // rate=0
        plugin.onBirth(child, environment);

        // Empty row should remain empty
        for (int x = 0; x <= 14; x++) {
            assertThat(environment.getMolecule(x, 4).value()).isEqualTo(0);
        }
    }

    @Test
    void copiedMoleculesHaveCorrectOwner() {
        // Set up a scenario where duplication will definitely happen
        // One code row with label at x=0, one completely empty row
        placeCodeRow(2, 0, 14, 0);
        placeEmptyOwnedRow(4, 0, 14);

        IRandomProvider rng = new SeededRandomProvider(123L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 3);
        plugin.onBirth(child, environment);

        // All non-empty cells should have child as owner
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 30; x++) {
                if (!environment.getMolecule(x, y).isEmpty()) {
                    int owner = environment.getOwnerId(x, y);
                    if (owner != 0) {
                        assertThat(owner).isEqualTo(child.getId());
                    }
                }
            }
        }
    }

    @Test
    void copyLengthLimitedBySourceEdge() {
        // Short code row: label at x=10, code ends at x=12 (only 3 molecules from label)
        for (int x = 10; x <= 12; x++) {
            Molecule mol = (x == 10)
                    ? new Molecule(Config.TYPE_LABEL, 99999)
                    : new Molecule(Config.TYPE_CODE, 42);
            environment.setMolecule(mol, child.getId(), new int[]{x, 2});
        }

        // Large empty row (20 cells) - much bigger than source
        placeEmptyOwnedRow(4, 0, 19);

        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 1.0, 2);
        plugin.onBirth(child, environment);

        // Count non-empty cells copied to y=4
        int copiedCount = 0;
        for (int x = 0; x <= 19; x++) {
            if (!environment.getMolecule(x, 4).isEmpty()) {
                copiedCount++;
            }
        }
        // Should copy at most 3 molecules (label + 2 code cells)
        assertThat(copiedCount).isLessThanOrEqualTo(3);
    }

    @Test
    void isStateless() {
        IRandomProvider rng = new SeededRandomProvider(42L);
        GeneDuplicationPlugin plugin = new GeneDuplicationPlugin(rng, 0.1, 5);

        byte[] state = plugin.saveState();
        assertThat(state).isEmpty();

        // loadState should not throw
        plugin.loadState(new byte[0]);
    }
}
