package org.evochora.runtime.spi;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DeathContext}.
 */
class DeathContextTest {

    private Environment environment;
    private DeathContext context;

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{10, 10}, true);
        context = new DeathContext();
    }

    @Test
    @Tag("unit")
    void throwsWhenNotInitialized() {
        assertThatThrownBy(() -> context.forEachOwnedCell(() -> {}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not initialized");
    }

    @Test
    @Tag("unit")
    void throwsWhenAccessedOutsideIteration() {
        // Setup: organism 1 owns cell at (5,5)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 42), 1, new int[]{5, 5});

        context.reset(environment, 1);

        assertThatThrownBy(() -> context.getMolecule())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("within forEachOwnedCell");

        assertThatThrownBy(() -> context.setMolecule(new Molecule(Config.TYPE_ENERGY, 100)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("within forEachOwnedCell");

        assertThatThrownBy(() -> context.getFlatIndex())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("within forEachOwnedCell");
    }

    @Test
    @Tag("unit")
    void iteratesOnlyOwnedCells() {
        // Setup: organism 1 owns cells at (1,1), (2,2), (3,3)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 10), 1, new int[]{1, 1});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 20), 1, new int[]{2, 2});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 30), 1, new int[]{3, 3});

        // Organism 2 owns cell at (5,5) - should not be visited
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 99), 2, new int[]{5, 5});

        context.reset(environment, 1);

        List<Integer> visitedValues = new ArrayList<>();
        context.forEachOwnedCell(() -> {
            visitedValues.add(context.getMolecule().value());
        });

        assertThat(visitedValues).containsExactlyInAnyOrder(10, 20, 30);
    }

    @Test
    @Tag("unit")
    void handlesOrganismWithNoCells() {
        // Organism 99 owns nothing
        context.reset(environment, 99);

        List<Integer> visited = new ArrayList<>();
        context.forEachOwnedCell(() -> visited.add(context.getFlatIndex()));

        assertThat(visited).isEmpty();
    }

    @Test
    @Tag("unit")
    void setMoleculeModifiesEnvironment() {
        // Setup: organism 1 owns cell at (4,4)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 50), 1, new int[]{4, 4});

        context.reset(environment, 1);

        context.forEachOwnedCell(() -> {
            context.setMolecule(new Molecule(Config.TYPE_ENERGY, 1000));
        });

        Molecule result = environment.getMolecule(4, 4);
        assertThat(result.type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(result.value()).isEqualTo(1000);
    }

    @Test
    @Tag("unit")
    void canBeReusedForMultipleOrganisms() {
        // First organism
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 1), 1, new int[]{0, 0});
        context.reset(environment, 1);

        List<Integer> firstValues = new ArrayList<>();
        context.forEachOwnedCell(() -> firstValues.add(context.getMolecule().value()));
        assertThat(firstValues).containsExactly(1);

        // Second organism (reusing same context)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 2), 2, new int[]{1, 1});
        context.reset(environment, 2);

        List<Integer> secondValues = new ArrayList<>();
        context.forEachOwnedCell(() -> secondValues.add(context.getMolecule().value()));
        assertThat(secondValues).containsExactly(2);
    }
}
