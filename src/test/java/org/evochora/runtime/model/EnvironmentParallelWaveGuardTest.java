package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.evochora.runtime.Config;
import org.evochora.runtime.TickWorkerPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every environment mutation is rejected inside the parallel wave of a tick, where
 * organisms execute concurrently against a snapshot of the environment. The guard is an
 * assertion, so this test relies on assertions being enabled in the test JVM. The worker pool
 * wraps a failure of its task, so the assertion error is found as the root cause.
 */
@Tag("unit")
class EnvironmentParallelWaveGuardTest {

    private final Environment env = new Environment(new EnvironmentProperties(new int[]{8, 8}, true));
    private TickWorkerPool pool;

    @AfterEach
    void shutdownPool() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void assertionsAreEnabledInTheTestJvm() {
        boolean enabled = false;
        assert enabled = true;
        assertThat(enabled).as("run tests with -ea; the guard is an assertion").isTrue();
    }

    @Test
    void mutations_areRejectedInsideTheParallelWave() {
        pool = new TickWorkerPool(2);
        Molecule data = new Molecule(Config.TYPE_DATA, 1);

        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> env.setMolecule(data, new int[]{from, 0})))
                .rootCause().isInstanceOf(AssertionError.class).hasMessageContaining("parallel wave");
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> env.setMolecule(data, 1, new int[]{from, 0})))
                .rootCause().isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> env.setMoleculeByIndex(from, data)))
                .rootCause().isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> env.setOwnerId(1, new int[]{from, 0})))
                .rootCause().isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> env.transferOwnership(1, 2, 0)))
                .rootCause().isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> env.clearOwnershipFor(1)))
                .rootCause().isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> env.clearMarkersFor(1, 0)))
                .rootCause().isInstanceOf(AssertionError.class);
    }

    @Test
    void mutations_workOutsideTheParallelWave() {
        Molecule data = new Molecule(Config.TYPE_DATA, 7);

        env.setMolecule(data, 1, new int[]{3, 3});

        assertThat(env.getMolecule(3, 3).toInt()).isEqualTo(data.toInt());
        assertThat(env.getOwnerId(3, 3)).isEqualTo(1);
    }
}
