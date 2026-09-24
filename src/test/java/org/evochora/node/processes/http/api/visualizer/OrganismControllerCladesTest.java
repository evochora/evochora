package org.evochora.node.processes.http.api.visualizer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.resources.database.dto.GenomeCarriers;
import org.evochora.node.processes.http.api.visualizer.dto.CladesResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the shape the clade endpoint answers in: genomes named once, referred to by position.
 */
@DisplayName("OrganismController clades response")
class OrganismControllerCladesTest {

    @Test
    @DisplayName("Samples on a grid that only grows at its end")
    void samplesOnAGridThatOnlyGrowsAtItsEnd() {
        // A run of 10 million ticks recorded every 10,000, sampled at most 60 times
        List<Long> before = OrganismController.sampleTicks(0L, 10_000_000L, 10_000, 60);

        assertThat(before).hasSizeLessThanOrEqualTo(60);
        assertThat(before.get(0)).isZero();
        assertThat(before).endsWith(10_000_000L);

        // The run grows: what was sampled stays sampled, the new ticks come at the end
        List<Long> after = OrganismController.sampleTicks(0L, 11_000_000L, 10_000, 60);

        assertThat(after).containsAll(before.subList(0, before.size() - 1));
        assertThat(after).endsWith(11_000_000L);
    }

    @Test
    @DisplayName("Doubles the step instead of moving the samples")
    void doublesTheStepInsteadOfMovingTheSamples() {
        List<Long> before = OrganismController.sampleTicks(0L, 10_000_000L, 10_000, 20);
        // Twice as long a run, same limit: the step doubles and every second sample remains
        List<Long> after = OrganismController.sampleTicks(0L, 20_000_000L, 10_000, 20);

        assertThat(after).hasSizeLessThanOrEqualTo(20);
        assertThat(after).containsAll(
                before.stream().filter(t -> t % (after.get(1) - after.get(0)) == 0).toList());
    }

    @Test
    @DisplayName("Samples every recorded tick of a short run")
    void samplesEveryRecordedTickOfAShortRun() {
        List<Long> ticks = OrganismController.sampleTicks(0L, 30_000L, 10_000, 60);

        assertThat(ticks).containsExactly(0L, 10_000L, 20_000L, 30_000L);
    }

    @Test
    @DisplayName("Names every genome once and points at parents by position")
    void namesGenomesOnceAndPointsAtParentsByPosition() {
        Map<Long, Long> lineage = new LinkedHashMap<>();
        lineage.put(100L, null);      // begins a line
        lineage.put(200L, 100L);
        lineage.put(300L, 200L);

        CladesResponseDto response = OrganismController.toCladesResponse(lineage, List.of());

        assertThat(response.genomes()).containsExactly("100", "200", "300");
        assertThat(response.parents()).containsExactly(-1, 0, 1);
    }

    @Test
    @DisplayName("Groups the carriers of a tick into one sample")
    void groupsCarriersOfATickIntoOneSample() {
        Map<Long, Long> lineage = new LinkedHashMap<>();
        lineage.put(100L, null);
        lineage.put(200L, 100L);

        CladesResponseDto response = OrganismController.toCladesResponse(lineage, List.of(
                new GenomeCarriers(10L, 100L, 3),
                new GenomeCarriers(10L, 200L, 1),
                new GenomeCarriers(20L, 200L, 5)));

        assertThat(response.samples()).hasSize(2);
        assertThat(response.samples().get(0).tick()).isEqualTo(10L);
        assertThat(response.samples().get(0).carriers())
                .containsExactly(new int[] { 0, 3 }, new int[] { 1, 1 });
        assertThat(response.samples().get(1).tick()).isEqualTo(20L);
        assertThat(response.samples().get(1).carriers()).containsExactly(new int[] { 1, 5 });
    }

    @Test
    @DisplayName("Names a genome that carries organisms but has no lineage entry")
    void namesAGenomeWithoutLineageEntry() {
        // A genome every carrier of which inherited it unchanged has no entry that begins a line,
        // and would otherwise be pointed at past the end of the list
        Map<Long, Long> lineage = new LinkedHashMap<>();
        lineage.put(100L, null);

        CladesResponseDto response = OrganismController.toCladesResponse(lineage, List.of(
                new GenomeCarriers(10L, 100L, 1),
                new GenomeCarriers(10L, 900L, 2)));

        assertThat(response.genomes()).containsExactly("100", "900");
        assertThat(response.parents()).containsExactly(-1, -1);
        assertThat(response.samples().get(0).carriers())
                .containsExactly(new int[] { 0, 1 }, new int[] { 1, 2 });
    }

    @Test
    @DisplayName("Keeps only the genomes the samples stand on, with their ancestors")
    void keepsOnlyWhatTheSamplesStandOn() {
        Map<Long, Long> lineage = new LinkedHashMap<>();
        lineage.put(100L, null);
        lineage.put(200L, 100L);
        lineage.put(300L, 200L);      // sampled: kept with 200 and 100
        lineage.put(400L, 100L);      // a line no sample touches

        CladesResponseDto response = OrganismController.toCladesResponse(
                OrganismController.ancestryOf(lineage, List.of(new GenomeCarriers(10L, 300L, 2))),
                List.of(new GenomeCarriers(10L, 300L, 2)));

        assertThat(response.genomes()).containsExactlyInAnyOrder("100", "200", "300");
        assertThat(response.genomes()).doesNotContain("400");
    }

    @Test
    @DisplayName("Leaves a parent outside the run as no parent")
    void leavesAParentOutsideTheRunAsNoParent() {
        Map<Long, Long> lineage = new LinkedHashMap<>();
        lineage.put(100L, 999L);      // parent not itself part of the lineage

        CladesResponseDto response = OrganismController.toCladesResponse(lineage, List.of());

        assertThat(response.genomes()).containsExactly("100");
        assertThat(response.parents()).containsExactly(-1);
    }
}
