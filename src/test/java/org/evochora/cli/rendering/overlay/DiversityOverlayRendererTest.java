package org.evochora.cli.rendering.overlay;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for DiversityOverlayRenderer.
 * Tests both rendering and diversity metric computation.
 */
@Tag("unit")
public class DiversityOverlayRendererTest {

    // ========================================================================
    // Rendering smoke tests
    // ========================================================================

    @Test
    void testRenderOntoSnapshot() {
        DiversityOverlayRenderer overlay = new DiversityOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(100)
            .addOrganisms(createOrganism(1, 42L, false))
            .addOrganisms(createOrganism(2, 43L, false))
            .build();

        overlay.render(image, snapshot);

        // Verify bottom-left pixels were changed (overlay draws there)
        int bottomLeftPixel = image.getRGB(10, image.getHeight() - 10);
        assertThat(bottomLeftPixel).isNotEqualTo(0);
    }

    @Test
    void testRenderOntoDelta() {
        DiversityOverlayRenderer overlay = new DiversityOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickDelta delta = TickDelta.newBuilder()
            .setTickNumber(100)
            .addOrganisms(createOrganism(1, 42L, false))
            .build();

        overlay.render(image, delta);

        int bottomLeftPixel = image.getRGB(10, image.getHeight() - 10);
        assertThat(bottomLeftPixel).isNotEqualTo(0);
    }

    @Test
    void testRenderOntoSmallImage() {
        DiversityOverlayRenderer overlay = new DiversityOverlayRenderer();
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder().setTickNumber(0).build();

        // Should not throw even on small images
        overlay.render(image, snapshot);
    }

    // ========================================================================
    // Diversity metric computation
    // ========================================================================

    @Test
    void testNoOrganisms_allZero() {
        DiversityOverlayRenderer overlay = new DiversityOverlayRenderer();

        overlay.computeDiversity(List.of());

        assertThat(overlay.getActiveGenomes()).isEqualTo(0);
        assertThat(overlay.getShannonIndex()).isEqualTo(0.0);
        assertThat(overlay.getEvenness()).isEqualTo(0.0);
    }

    @Test
    void testSingleGenome_shannonZero_evennessOne() {
        DiversityOverlayRenderer overlay = new DiversityOverlayRenderer();

        List<OrganismState> organisms = List.of(
            createOrganism(1, 100L, false),
            createOrganism(2, 100L, false),
            createOrganism(3, 100L, false)
        );

        overlay.computeDiversity(organisms);

        assertThat(overlay.getActiveGenomes()).isEqualTo(1);
        assertThat(overlay.getShannonIndex()).isEqualTo(0.0);
        assertThat(overlay.getEvenness()).isEqualTo(1.0);
    }

    @Test
    void testTwoEqualGenomes_shannonIsLn2_evennessOne() {
        DiversityOverlayRenderer overlay = new DiversityOverlayRenderer();

        List<OrganismState> organisms = List.of(
            createOrganism(1, 100L, false),
            createOrganism(2, 200L, false)
        );

        overlay.computeDiversity(organisms);

        assertThat(overlay.getActiveGenomes()).isEqualTo(2);
        assertThat(overlay.getShannonIndex()).isCloseTo(Math.log(2), within(0.001));
        assertThat(overlay.getEvenness()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void testUnevenDistribution_evennessLessThanOne() {
        DiversityOverlayRenderer overlay = new DiversityOverlayRenderer();

        // 9 organisms of genome A, 1 of genome B → dominant genome
        List<OrganismState> organisms = List.of(
            createOrganism(1, 100L, false),
            createOrganism(2, 100L, false),
            createOrganism(3, 100L, false),
            createOrganism(4, 100L, false),
            createOrganism(5, 100L, false),
            createOrganism(6, 100L, false),
            createOrganism(7, 100L, false),
            createOrganism(8, 100L, false),
            createOrganism(9, 100L, false),
            createOrganism(10, 200L, false)
        );

        overlay.computeDiversity(organisms);

        assertThat(overlay.getActiveGenomes()).isEqualTo(2);
        assertThat(overlay.getShannonIndex()).isGreaterThan(0.0);
        assertThat(overlay.getShannonIndex()).isLessThan(Math.log(2));
        assertThat(overlay.getEvenness()).isLessThan(1.0);
        assertThat(overlay.getEvenness()).isGreaterThan(0.0);
    }

    @Test
    void testDeadOrganisms_excluded() {
        DiversityOverlayRenderer overlay = new DiversityOverlayRenderer();

        List<OrganismState> organisms = List.of(
            createOrganism(1, 100L, false),
            createOrganism(2, 200L, true),   // dead — should be excluded
            createOrganism(3, 300L, true)    // dead — should be excluded
        );

        overlay.computeDiversity(organisms);

        assertThat(overlay.getActiveGenomes()).isEqualTo(1);
    }

    @Test
    void testZeroGenomeHash_excluded() {
        DiversityOverlayRenderer overlay = new DiversityOverlayRenderer();

        List<OrganismState> organisms = List.of(
            createOrganism(1, 0L, false),    // zero hash — excluded
            createOrganism(2, 100L, false)
        );

        overlay.computeDiversity(organisms);

        assertThat(overlay.getActiveGenomes()).isEqualTo(1);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private OrganismState createOrganism(int id, long genomeHash, boolean dead) {
        OrganismState.Builder builder = OrganismState.newBuilder()
            .setOrganismId(id)
            .setGenomeHash(genomeHash);
        if (dead) {
            builder.setIsDead(true);
        }
        return builder.build();
    }
}
