package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

@Tag("unit")
class InstructionUsagePluginTest {

    private InstructionUsagePlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new InstructionUsagePlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "instructions")));
        plugin.initialize(null);
    }

    @Test
    void testSchema_IsCreatedDynamically() {
        // We know from Instruction.java that there are at least these families
        assertThat(plugin.getSchema().getColumns().stream().map(c -> c.name())).contains("tick", "arithmetic", "controlflow", "data");
    }

    @Test
    void testExtractRows_CountsFamiliesCorrectly() {
        // Use the new public API to get a valid opcode
        Instruction.InstructionInfo addrInfo = Instruction.getInstructionSetInfo().stream()
            .filter(info -> info.name().equals("ADDR")).findFirst().orElseThrow();
        Instruction.InstructionInfo jmpiInfo = Instruction.getInstructionSetInfo().stream()
            .filter(info -> info.name().equals("JMPI")).findFirst().orElseThrow();

        TickData tick = TickData.newBuilder()
            .setTickNumber(123L)
            .addOrganisms(OrganismState.newBuilder().setInstructionOpcodeId(addrInfo.opcodeId()))
            .addOrganisms(OrganismState.newBuilder().setInstructionOpcodeId(addrInfo.opcodeId()))
            .addOrganisms(OrganismState.newBuilder().setInstructionOpcodeId(jmpiInfo.opcodeId()))
            .addOrganisms(OrganismState.newBuilder()) // 1 with no instruction
            .build();

        List<Object[]> rows = plugin.extractRows(tick);
        assertThat(rows).hasSize(1);
        
        Object[] row = rows.get(0);
        
        List<String> columnNames = plugin.getSchema().getColumns().stream().map(c -> c.name()).collect(Collectors.toList());
        int tickIndex = columnNames.indexOf("tick");
        int arithIndex = columnNames.indexOf("arithmetic");
        int cfIndex = columnNames.indexOf("controlflow");
        int dataIndex = columnNames.indexOf("data");

        assertThat(row[tickIndex]).isEqualTo(123L);
        assertThat(row[arithIndex]).isEqualTo(2);
        assertThat(row[cfIndex]).isEqualTo(1);
        assertThat(row[dataIndex]).isEqualTo(0);
    }
}
