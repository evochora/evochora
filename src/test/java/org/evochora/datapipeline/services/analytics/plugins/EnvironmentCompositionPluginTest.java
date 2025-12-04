package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.runtime.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

@Tag("unit")
class EnvironmentCompositionPluginTest {

    private EnvironmentCompositionPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new EnvironmentCompositionPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "env")));
        // We will not initialize with a mocked context to avoid classpath issues.
        // This means the test can only verify counting logic on provided cells,
        // not the calculation of implicit empty cells.
        plugin.initialize(null);
    }

    @Test
    void testExtractRows_ExactMode_CountsProvidedCellsCorrectly() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1L)
            .addCells(CellState.newBuilder().setMoleculeType(Config.TYPE_CODE).setMoleculeValue(1).build()) // 1 code
            .addCells(CellState.newBuilder().setMoleculeType(Config.TYPE_CODE).setMoleculeValue(2).build()) // 2 code
            .addCells(CellState.newBuilder().setMoleculeType(Config.TYPE_DATA).build())   // 1 data
            .addCells(CellState.newBuilder().setMoleculeType(Config.TYPE_ENERGY).build())  // 1 energy
            .addCells(CellState.newBuilder().setMoleculeType(Config.TYPE_STRUCTURE).build()) // 1 structure
            .addCells(CellState.newBuilder().setMoleculeType(Config.TYPE_CODE).setMoleculeValue(0).setOwnerId(0).build()) // 1 explicit empty
            .build(); // Total 6 cells sent

        List<Object[]> rows = plugin.extractRows(tick);
        Object[] row = rows.get(0);

        assertThat(row[1]).isEqualTo(2L); // code
        assertThat(row[2]).isEqualTo(1L); // data
        assertThat(row[3]).isEqualTo(1L); // energy
        assertThat(row[4]).isEqualTo(1L); // structure
        // Without context, empty cells = only those explicitly in the list (CODE:0, Owner:0)
        assertThat(row[5]).isEqualTo(1L); // empty
    }

    @Test
    void testExtractRows_SamplingMode_DoesNotCrash() {
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "env", "monteCarloSamples", 2)));
        plugin.initialize(null);
        
        TickData.Builder builder = TickData.newBuilder().setTickNumber(1L);
        // 50 code, 50 data
        for(int i=0; i<50; i++) builder.addCells(CellState.newBuilder().setMoleculeType(Config.TYPE_CODE).setMoleculeValue(1).build());
        for(int i=0; i<50; i++) builder.addCells(CellState.newBuilder().setMoleculeType(Config.TYPE_DATA).build());
        
        List<Object[]> rows = plugin.extractRows(builder.build());
        Object[] row = rows.get(0);
        
        // We can't assert the exact extrapolated values, but we can ensure it ran.
        assertThat(row).hasSize(6);
        assertThat(row[5]).isEqualTo(-1L); // empty cells are marked as unknown in sampling mode
    }
}
