package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.CellStateTestHelper;
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
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCell(Config.TYPE_CODE, 1), // code
                CellStateTestHelper.createCell(Config.TYPE_CODE, 2), // code
                CellStateTestHelper.createCell(Config.TYPE_DATA),   // data
                CellStateTestHelper.createCell(Config.TYPE_ENERGY),  // energy
                CellStateTestHelper.createCell(Config.TYPE_STRUCTURE), // structure
                CellStateTestHelper.createCell(Config.TYPE_LABEL, 12345), // label (fuzzy jump target)
                CellStateTestHelper.createCellStateBuilder(0, 1, Config.TYPE_CODE, 0, 0).build(), // CODE:0 → empty (not counted as code)
                CellStateTestHelper.createCell(99) // unknown type
            )))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);
        Object[] row = rows.get(0);

        // Schema: tick, code, data, energy, structure, label, labelref, unknown, empty (9 columns)
        assertThat(row[0]).isEqualTo(1L);  // tick
        assertThat(row[1]).isEqualTo(2L);  // code (only CODE with value != 0)
        assertThat(row[2]).isEqualTo(1L);  // data
        assertThat(row[3]).isEqualTo(1L);  // energy
        assertThat(row[4]).isEqualTo(1L);  // structure
        assertThat(row[5]).isEqualTo(1L);  // label
        assertThat(row[6]).isEqualTo(0L);  // labelref
        assertThat(row[7]).isEqualTo(1L);  // unknown (type 99)
        // empty = totalCells - (code + data + energy + structure + label + labelref + unknown)
        // Without context, totalCells = 0, so empty = max(0, 0 - 7) = 0
        assertThat(row[8]).isEqualTo(0L);  // empty
    }

    @Test
    void testNeedsEnvironmentData_returnsTrue() {
        assertThat(plugin.needsEnvironmentData()).isTrue();
    }
    
    @Test
    void testExtractRows_SamplingMode_DoesNotCrash() {
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "env", "monteCarloSamples", 2)));
        plugin.initialize(null);

        TickData.Builder builder = TickData.newBuilder().setTickNumber(1L);
        // 50 code, 50 data
        List<CellState> cells = new ArrayList<>();
        for(int i=0; i<50; i++) cells.add(CellStateTestHelper.createCell(Config.TYPE_CODE, 1));
        for(int i=0; i<50; i++) cells.add(CellStateTestHelper.createCell(Config.TYPE_DATA));
        builder.setCellColumns(CellStateTestHelper.createColumnsFromCells(cells));

        List<Object[]> rows = plugin.extractRows(builder.build());
        Object[] row = rows.get(0);

        // Schema: tick, code, data, energy, structure, label, labelref, unknown, empty (9 columns)
        assertThat(row).hasSize(9);
        // Without context (totalCells = 0), empty = max(0, 0 - sum) = 0
        assertThat(row[8]).isEqualTo(0L);
    }
}
