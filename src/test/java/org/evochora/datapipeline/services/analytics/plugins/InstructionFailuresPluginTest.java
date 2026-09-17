package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for InstructionFailuresPlugin: failed instructions counted by instruction and by the
 * failure text, one recording at a time.
 */
@Tag("unit")
class InstructionFailuresPluginTest {

    private InstructionFailuresPlugin plugin;
    private int addr;
    private int jmpi;

    @BeforeEach
    void setUp() {
        plugin = new InstructionFailuresPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "instruction_failures")));
        plugin.initialize(null);
        addr = opcode("ADDR");
        jmpi = opcode("JMPI");
    }

    @Test
    void countsFailuresByInstructionAndText() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(7)
            .addOrganisms(failed(addr, "Invalid register ID: 17"))
            .addOrganisms(failed(addr, "Invalid register ID: 17"))
            .addOrganisms(failed(addr, "Invalid register ID: 18"))
            .addOrganisms(failed(jmpi, "No matching label"))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).containsExactly(
            new Object[] {7L, "ADDR", "Invalid register ID: 17", 2},
            new Object[] {7L, "ADDR", "Invalid register ID: 18", 1},
            new Object[] {7L, "JMPI", "No matching label", 1});
    }

    @Test
    void onlyLivingOrganismsWithAFailedInstructionCount() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(7)
            .addOrganisms(OrganismState.newBuilder().setInstructionOpcodeId(addr).setInstructionFailed(false))
            .addOrganisms(failed(addr, "Ran out of energy").toBuilder().setIsDead(true))
            .addOrganisms(OrganismState.newBuilder().setInstructionFailed(true).setFailureReason("no opcode"))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void eachRecordingStandsOnItsOwn() {
        TickData first = TickData.newBuilder().setTickNumber(1).addOrganisms(failed(addr, "x")).build();
        TickData second = TickData.newBuilder().setTickNumber(2).addOrganisms(failed(addr, "x")).build();

        plugin.extractRows(first);
        List<Object[]> rows = plugin.extractRows(second);

        assertThat(rows).containsExactly(new Object[] {2L, "ADDR", "x", 1});
    }

    @Test
    void hasNoChartOfItsOwn() {
        assertThat(plugin.getManifestEntry()).isNull();
    }

    private static OrganismState failed(int opcode, String reason) {
        return OrganismState.newBuilder()
            .setInstructionOpcodeId(opcode)
            .setInstructionFailed(true)
            .setFailureReason(reason)
            .build();
    }

    private static int opcode(String name) {
        return Instruction.getInstructionSetInfo().stream()
            .filter(info -> info.name().equals(name))
            .findFirst()
            .orElseThrow()
            .opcodeId();
    }
}
