package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the rule a death's cause is read by.
 * <p>
 * Test metadata uses max-entropy=8191 from TestMetadataHelper.
 */
@Tag("unit")
class DeathCauseTest {

    /** Max entropy from TestMetadataHelper defaults. */
    private static final int MAX_ENTROPY = 8191;

    private final DeathCause cause = DeathCause.forRun("deaths", contextOfARun());

    @Test
    void energyUsedUpIsADeathByEnergy() {
        assertThat(cause.of(dead(0, 10))).isEqualTo(DeathCause.ENERGY);
        assertThat(cause.of(dead(-5, 10))).isEqualTo(DeathCause.ENERGY);
    }

    @Test
    void entropyAboveTheMaximumIsADeathByEntropy() {
        assertThat(cause.of(dead(50, MAX_ENTROPY + 1))).isEqualTo(DeathCause.ENTROPY);
    }

    @Test
    void energyIsCheckedFirstAsTheRuntimeDoes() {
        assertThat(cause.of(dead(0, MAX_ENTROPY + 1))).isEqualTo(DeathCause.ENERGY);
    }

    @Test
    void reachingTheEntropyMaximumIsNoCause() {
        assertThatThrownBy(() -> cause.of(dead(50, MAX_ENTROPY)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("neither")
            .hasMessageContaining(String.valueOf(MAX_ENTROPY));
    }

    @Test
    void aContextWithoutARunConfigurationIsRefused() {
        IAnalyticsContext withoutConfiguration = contextWith(SimulationMetadata.getDefaultInstance());

        assertThatThrownBy(() -> DeathCause.forRun("deaths", withoutConfiguration))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deaths")
            .hasMessageContaining("run configuration");
    }

    @Test
    void theCausesAreNumberedInTheOrderTheyAreListedIn() {
        assertThat(DeathCause.numberedCauses())
            .isEqualTo("0 (energy): the organism's energy was used up. "
                + "1 (entropy): the organism's entropy exceeded the maximum.");
    }

    @Test
    void anUnknownCauseHasNoMeaning() {
        assertThatThrownBy(() -> DeathCause.meaningOf("other"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static OrganismState dead(int energy, int entropy) {
        return OrganismState.newBuilder()
            .setIsDead(true)
            .setEnergy(energy)
            .setEntropyRegister(entropy)
            .build();
    }

    private static IAnalyticsContext contextOfARun() {
        return contextWith(SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(TestMetadataHelper.builder().build())
            .build());
    }

    private static IAnalyticsContext contextWith(SimulationMetadata metadata) {
        return new IAnalyticsContext() {
            @Override
            public SimulationMetadata getMetadata() {
                return metadata;
            }

            @Override
            public String getRunId() {
                return "test-run";
            }

            @Override
            public PublishedOutputStream openArtifactStream(String metricId, String lodLevel, String filename)
                    throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.nio.file.Path getTempDirectory() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
