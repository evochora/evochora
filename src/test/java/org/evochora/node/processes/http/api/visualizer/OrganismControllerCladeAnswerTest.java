package org.evochora.node.processes.http.api.visualizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.dto.GenomeCarriers;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.processes.http.api.visualizer.dto.CladesResponseDto;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.ConfigFactory;

/**
 * Tests that a built clade answer is kept until the sampled ticks move.
 * <p>
 * Building one reads the descent of the whole run and counts its organisms. A run grows by a tick
 * every few milliseconds while its sampled ticks move once in many minutes, so between two of
 * those the same answer is asked for again and again.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
@DisplayName("OrganismController clade answers")
// The controller is built here without an options block, which it says so about.
@AllowLog(level = LogLevel.WARN, messagePattern = "No configuration for .*")
class OrganismControllerCladeAnswerTest {

    private static OrganismController controller() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, mock(IDatabaseReaderProvider.class));
        return new OrganismController(registry, ConfigFactory.empty());
    }

    private static IDatabaseReader readerReturning(long genome, int carriers) throws Exception {
        IDatabaseReader reader = mock(IDatabaseReader.class);
        Map<Long, Long> lineage = new LinkedHashMap<>();
        lineage.put(genome, null);
        when(reader.readGenomeLineage()).thenReturn(lineage);
        when(reader.readGenomeCounts(List.of(10L, 20L)))
                .thenReturn(List.of(new GenomeCarriers(20L, genome, carriers)));
        return reader;
    }

    @Test
    @DisplayName("Asks the database once for the same sampled ticks")
    void asksTheDatabaseOnceForTheSameSampledTicks() throws Exception {
        OrganismController controller = controller();
        IDatabaseReader reader = readerReturning(700L, 3);

        CladesResponseDto first = controller.answerFor(reader, "run:20", List.of(10L, 20L));
        CladesResponseDto second = controller.answerFor(reader, "run:20", List.of(10L, 20L));

        assertThat(second).isSameAs(first);
        verify(reader, times(1)).readGenomeLineage();
        verify(reader, times(1)).readGenomeCounts(List.of(10L, 20L));
    }

    @Test
    @DisplayName("Builds again once the sampled ticks have moved")
    void buildsAgainOnceTheSampledTicksHaveMoved() throws Exception {
        OrganismController controller = controller();
        IDatabaseReader reader = readerReturning(700L, 3);

        controller.answerFor(reader, "run:20", List.of(10L, 20L));
        controller.answerFor(reader, "run:30", List.of(10L, 20L));

        verify(reader, times(2)).readGenomeLineage();
    }

    @Test
    @DisplayName("Keeps the answers of two runs apart")
    void keepsTheAnswersOfTwoRunsApart() throws Exception {
        OrganismController controller = controller();
        IDatabaseReader one = readerReturning(700L, 3);
        IDatabaseReader other = readerReturning(800L, 5);

        CladesResponseDto first = controller.answerFor(one, "run-a:20", List.of(10L, 20L));
        CladesResponseDto second = controller.answerFor(other, "run-b:20", List.of(10L, 20L));

        assertThat(first.genomes()).containsExactly("700");
        assertThat(second.genomes()).containsExactly("800");
    }
}
