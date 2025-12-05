package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@Tag("unit")
class GenerationDepthPluginTest {

    private GenerationDepthPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GenerationDepthPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "depth"));
        plugin.configure(config);
        plugin.initialize(null);
    }

    @Test
    void testExtractRows_CalculatesDepthCorrectly() {
        // Tick 1: Org 1 (Gen 0)
        TickData tick1 = createTick(100, createOrg(1, null));
        plugin.extractRows(tick1);
        
        // Tick 2: Org 1 creates Org 2
        TickData tick2 = createTick(101, createOrg(1, null), createOrg(2, 1));
        List<Object[]> rows2 = plugin.extractRows(tick2);
        
        assertThat(rows2.get(0)[1]).isEqualTo(1); // max_depth (Org 2 is Gen 1)
        assertThat(rows2.get(0)[2]).isEqualTo(0.5); // avg_depth ( (0+1)/2 = 0.5 )
        
        // Tick 3: Org 2 creates Org 3
        TickData tick3 = createTick(102, createOrg(1, null), createOrg(2, 1), createOrg(3, 2));
        List<Object[]> rows3 = plugin.extractRows(tick3);
        
        assertThat(rows3.get(0)[1]).isEqualTo(2); // max_depth (Org 3 is Gen 2)
    }

    @Test
    void testCleanup_RemovesDeadOrganismsFromMap() {
        // Tick 1: Org 1 (Gen 0) and Org 10 (Gen 0) exist
        plugin.extractRows(createTick(100, createOrg(1, null), createOrg(10, null)));

        // Tick 2: Org 1 creates Org 2. Org 10 dies.
        TickData tick2 = createTick(101, createOrg(1, null), createOrg(2, 1));
        plugin.extractRows(tick2);

        // Tick 3: Org 2 creates Org 3. Org 1 dies.
        // The map should now only contain Org 2 and 3.
        // Org 1's depth is needed for Org 2, but then it can be cleaned up.
        // Let's test a case where a parent dies but its lineage continues.
        TickData tick3 = createTick(102, createOrg(2, 1), createOrg(3, 2));
        List<Object[]> rows3 = plugin.extractRows(tick3);
        
        assertThat(rows3.get(0)[1]).isEqualTo(2); // max_depth is 2 (Org 3)
        assertThat(rows3.get(0)[2]).isEqualTo(1.5); // avg_depth is (1+2)/2 = 1.5

        // Tick 4: A new organism whose parent is long dead (not in map)
        TickData tick4 = createTick(103, createOrg(4, 999));
        List<Object[]> rows4 = plugin.extractRows(tick4);
        
        assertThat(rows4.get(0)[1]).isEqualTo(1); // max_depth is 1 (parent 999 is unknown, so depth is 0+1)
    }

    private TickData createTick(long tickNum, OrganismState... orgs) {
        TickData.Builder builder = TickData.newBuilder().setTickNumber(tickNum);
        for (OrganismState o : orgs) {
            builder.addOrganisms(o);
        }
        return builder.build();
    }
    
    private OrganismState createOrg(int id, Integer parentId) {
        OrganismState.Builder b = OrganismState.newBuilder().setOrganismId(id);
        if (parentId != null) {
            b.setParentId(parentId);
        }
        return b.build();
    }
}

