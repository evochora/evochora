package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.label.PreExpandedHammingStrategy;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contains unit tests for the {@link EnergyVaultCreator} world generation strategy: the geometry of
 * a vault in two and three dimensions, the refill of emptied core cells, and the state it carries
 * across a resume. They operate on an in-memory environment and do not require external resources.
 */
public class EnergyVaultStrategyTest {

    /** Tile side 1 keeps the cell count of a world too small to be tiled. */
    private static final int TILE_SIDE = 1;

    private static final int ENERGY = 7000;
    private static final int HARDNESS = 400;
    private static final int SHELL_OWNER = -1;

    /**
     * Verifies that a vault in a 2D world consists of an unowned energy core and a shell of
     * structure molecules carrying the configured negative owner, and that the diagonal cells a
     * cube-shaped shell would also cover are left untouched.
     * This is a unit test for the vault geometry.
     */
    @Test
    @Tag("unit")
    void placesUnownedCoreInsideAShellOwnedByANonOrganism_in2D() {
        Environment environment = world(9, 9);
        creator(0.02, 0, 1, 500).execute(simulationAt(environment, 0L));

        int[] center = singleCoreCell(environment);
        assertThat(environment.getMolecule(center).type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(environment.getMolecule(center).toScalarValue()).isEqualTo(ENERGY);
        assertThat(environment.getOwnerId(center)).isZero();

        for (int axis = 0; axis < 2; axis++) {
            for (int delta : new int[]{-1, 1}) {
                int[] neighbour = center.clone();
                neighbour[axis] += delta;
                assertThat(environment.getMolecule(neighbour).type()).isEqualTo(Config.TYPE_STRUCTURE);
                assertThat(environment.getMolecule(neighbour).toScalarValue()).isEqualTo(HARDNESS);
                assertThat(environment.getOwnerId(neighbour)).isEqualTo(SHELL_OWNER);
            }
        }

        // A step can only move along one axis, so the diagonal cells need no shell and get none.
        for (int dx : new int[]{-1, 1}) {
            for (int dy : new int[]{-1, 1}) {
                assertThat(environment.getMolecule(center[0] + dx, center[1] + dy).isEmpty()).isTrue();
                assertThat(environment.getOwnerId(center[0] + dx, center[1] + dy)).isZero();
            }
        }
    }

    /**
     * Verifies that the shell encloses the core on every axis of a 3D world, which is what makes it
     * impassable there: a vault is described by Manhattan distance, so its cell count grows with
     * twice the number of dimensions rather than exponentially.
     * This is a unit test for the vault geometry in n dimensions.
     */
    @Test
    @Tag("unit")
    void enclosesTheCoreOnEveryAxis_in3D() {
        Environment environment = world(7, 7, 7);
        creator(0.001, 0, 1, 500).execute(simulationAt(environment, 0L));

        int[] center = singleCoreCell(environment);
        for (int axis = 0; axis < 3; axis++) {
            for (int delta : new int[]{-1, 1}) {
                int[] neighbour = center.clone();
                neighbour[axis] += delta;
                assertThat(environment.getMolecule(neighbour).type()).isEqualTo(Config.TYPE_STRUCTURE);
                assertThat(environment.getOwnerId(neighbour)).isEqualTo(SHELL_OWNER);
            }
        }
        assertThat(environment.countCellsOwnedBy(SHELL_OWNER)).isEqualTo(6);
    }

    /**
     * Verifies that a core of radius 1 holds every cell within one axis step of the centre and that
     * the shell sits on the cells exactly two steps away.
     * This is a unit test for the radius parameters.
     */
    @Test
    @Tag("unit")
    void growsCoreAndShellWithTheRadii() {
        Environment environment = world(11, 11);
        creator(0.01, 1, 1, 500).execute(simulationAt(environment, 0L));

        List<int[]> coreCells = cellsOfType(environment, Config.TYPE_ENERGY);
        assertThat(coreCells).hasSize(5);
        assertThat(environment.countCellsOwnedBy(SHELL_OWNER)).isEqualTo(8);
        for (int[] cell : coreCells) {
            assertThat(environment.getOwnerId(cell)).isZero();
        }
    }

    /**
     * Verifies that an emptied core cell is refilled on the next interval tick, and not before it.
     * This is a unit test for the refill schedule.
     */
    @Test
    @Tag("unit")
    void refillsAnEmptiedCoreCellOnTheNextIntervalTick() {
        Environment environment = world(9, 9);
        EnergyVaultCreator creator = creator(0.02, 0, 1, 10);
        creator.execute(simulationAt(environment, 0L));

        int[] center = singleCoreCell(environment);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), 0, center);
        assertThat(environment.getMolecule(center).isEmpty()).isTrue();

        creator.execute(simulationAt(environment, 5L));
        assertThat(environment.getMolecule(center).isEmpty()).isTrue();

        creator.execute(simulationAt(environment, 10L));
        assertThat(environment.getMolecule(center).type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(environment.getOwnerId(center)).isZero();
    }

    /**
     * Verifies that a core cell an organism has taken over is left alone, whether it holds one of
     * that organism's molecules or has just been emptied while the owner still stands, as it does
     * between a death handler and the release of the dead organism's cells. The refill would
     * otherwise write into a body.
     * This is a unit test for the refill's ownership check.
     */
    @Test
    @Tag("unit")
    void leavesACoreCellTakenOverByAnOrganismAlone() {
        Environment environment = world(9, 9);
        EnergyVaultCreator creator = creator(0.02, 0, 1, 10);
        creator.execute(simulationAt(environment, 0L));
        int[] center = singleCoreCell(environment);

        environment.setMolecule(new Molecule(Config.TYPE_CODE, 5), 42, center);
        creator.execute(simulationAt(environment, 10L));
        assertThat(environment.getOwnerId(center)).isEqualTo(42);
        assertThat(environment.getMolecule(center).toScalarValue()).isEqualTo(5);

        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), 42, center);
        creator.execute(simulationAt(environment, 20L));
        assertThat(environment.getOwnerId(center)).isEqualTo(42);
        assertThat(environment.getMolecule(center).isEmpty()).isTrue();
    }

    /**
     * Verifies that the saved state carries the core cells, that a restored creator refills them,
     * and that it neither places new vaults nor rebuilds a shell an organism has breached.
     * This is a unit test for checkpoint serialization.
     */
    @Test
    @Tag("unit")
    void restoresTheCoreCellsWithoutPlacingOrRepairingAnything() {
        Environment environment = world(9, 9);
        EnergyVaultCreator creator = creator(0.02, 0, 1, 10);
        creator.execute(simulationAt(environment, 0L));
        int[] center = singleCoreCell(environment);

        byte[] state = creator.saveState();
        EnergyVaultCreator restored = creator(0.02, 0, 1, 10);
        restored.loadState(state);

        // A breach the organism paid for: one shell cell emptied, and the core taken.
        int[] breach = center.clone();
        breach[0] += 1;
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), 0, breach);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), 0, center);

        restored.execute(simulationAt(environment, 10L));

        assertThat(environment.getMolecule(center).type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(environment.getMolecule(breach).isEmpty()).isTrue();
        assertThat(environment.getOwnerId(breach)).isZero();
        assertThat(cellsOfType(environment, Config.TYPE_ENERGY)).hasSize(1);
        assertThat(environment.countCellsOwnedBy(SHELL_OWNER)).isEqualTo(3);
    }

    /**
     * Verifies that a state saved before the first execution restores a creator that still places
     * its vaults.
     * This is a unit test for checkpoint serialization.
     */
    @Test
    @Tag("unit")
    void restoresAnUninitializedCreatorAsUnplaced() {
        EnergyVaultCreator creator = creator(0.02, 0, 1, 10);
        assertThat(creator.saveState()).isEmpty();

        EnergyVaultCreator restored = creator(0.02, 0, 1, 10);
        restored.loadState(new byte[0]);
        Environment environment = world(9, 9);
        restored.execute(simulationAt(environment, 0L));

        assertThat(cellsOfType(environment, Config.TYPE_ENERGY)).hasSize(1);
    }

    /**
     * Verifies that an owner id an organism could hold is rejected, and that a world too small for
     * the configured radius fails instead of placing a clipped vault.
     * This is a unit test for parameter validation.
     */
    @Test
    @Tag("unit")
    void rejectsAnOwnerAnOrganismCouldHoldAndAWorldTooSmall() {
        assertThatThrownBy(() -> new EnergyVaultCreator(
                new SeededRandomProvider(0L), 0.02, 0, 1, ENERGY, HARDNESS, 1, 500, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("structureOwner");

        assertThatThrownBy(() -> new EnergyVaultCreator(
                new SeededRandomProvider(0L), 0.02, 0, 1, ENERGY, HARDNESS, 0, 500, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("structureOwner");

        Environment tiny = world(2, 2);
        assertThatThrownBy(() -> creator(0.5, 0, 1, 500).execute(simulationAt(tiny, 0L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not fit");
    }

    /**
     * Verifies that a non-toroidal world keeps its edge free of vaults, so that no vault is written
     * incompletely.
     * This is a unit test for placement in a bounded world.
     */
    @Test
    @Tag("unit")
    void keepsTheEdgeOfANonToroidalWorldFree() {
        Environment environment = new Environment(
                new EnvironmentProperties(new int[]{5, 5}, false), new PreExpandedHammingStrategy(), TILE_SIDE);
        // Every cell of the world is drawn many times over, so a centre on the edge would be used
        // if it were accepted.
        creator(0.04, 0, 1, 500).execute(simulationAt(environment, 0L));

        int[] center = singleCoreCell(environment);
        assertThat(center[0]).isBetween(1, 3);
        assertThat(center[1]).isBetween(1, 3);
    }

    /**
     * Builds a toroidal world of the given shape.
     *
     * @param shape One extent per dimension.
     * @return A new environment.
     */
    private static Environment world(int... shape) {
        return new Environment(new EnvironmentProperties(shape, true),
                new PreExpandedHammingStrategy(), TILE_SIDE);
    }

    /**
     * Builds a creator with the test's energy, hardness and owner.
     *
     * @param percentage Fraction of cells to place as vault centres.
     * @param coreRadius Manhattan radius of the core.
     * @param wallThickness Shell layers around the core.
     * @param interval Ticks between refills.
     * @return A new creator.
     */
    private static EnergyVaultCreator creator(double percentage, int coreRadius, int wallThickness, int interval) {
        return new EnergyVaultCreator(new SeededRandomProvider(0L), percentage, coreRadius,
                wallThickness, ENERGY, HARDNESS, SHELL_OWNER, interval, 0);
    }

    /**
     * A simulation that reports the given tick and the given environment.
     *
     * @param environment The environment to hand out.
     * @param tick The tick to report.
     * @return The mocked simulation.
     */
    private static Simulation simulationAt(Environment environment, long tick) {
        Simulation simulation = mock(Simulation.class);
        when(simulation.getEnvironment()).thenReturn(environment);
        when(simulation.getCurrentTick()).thenReturn(tick);
        return simulation;
    }

    /**
     * The coordinate of the only energy cell in the world.
     *
     * @param environment The environment to search.
     * @return That cell's coordinate.
     */
    private static int[] singleCoreCell(Environment environment) {
        List<int[]> cells = cellsOfType(environment, Config.TYPE_ENERGY);
        assertThat(cells).hasSize(1);
        return cells.get(0);
    }

    /**
     * Collects the coordinates of every cell holding a molecule of the given type.
     *
     * @param environment The environment to walk.
     * @param type The molecule type to collect.
     * @return The coordinates, in flat index order.
     */
    private static List<int[]> cellsOfType(Environment environment, int type) {
        List<int[]> found = new ArrayList<>();
        int[] coord = new int[environment.getProperties().getDimensions()];
        for (int flatIndex = 0; flatIndex < environment.getTotalCells(); flatIndex++) {
            environment.getProperties().flatIndexToCoordinates(flatIndex, coord);
            if (environment.getMolecule(coord).type() == type) {
                found.add(coord.clone());
            }
        }
        return found;
    }
}
