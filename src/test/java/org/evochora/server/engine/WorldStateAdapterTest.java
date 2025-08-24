package org.evochora.server.engine;

import org.evochora.runtime.Simulation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class WorldStateAdapterTest {

    @Test
    void producesPreparedTickStateWithExpectedPrefixes() {
        Simulation sim = new Simulation(new org.evochora.runtime.model.Environment(new int[]{10,10}, true), true);
        PreparedTickState pts = WorldStateAdapter.toPreparedState(sim);
        assertThat(pts).isNotNull();
        assertThat(pts.mode()).isEqualTo("performance");
        assertThat(pts.worldMeta()).isNotNull();
        assertThat(pts.worldMeta().shape()).containsExactly(10,10);
        assertThat(pts.worldState()).isNotNull();
    }
}


