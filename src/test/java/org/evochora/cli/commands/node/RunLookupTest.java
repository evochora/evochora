package org.evochora.cli.commands.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigUtil;

/**
 * Tests for the lookup of the newest run, which {@code node resume} and {@code node fork} use when
 * the command line names no run.
 */
@Tag("integration")
class RunLookupTest {

    private static final String OLDER_RUN = "20260101-12000000-1111aaaa";
    private static final String NEWER_RUN = "20260102-09301500-2222bbbb";

    @TempDir
    Path storageDir;

    @Test
    void theNewestOfSeveralRunsIsFound() throws Exception {
        writeMetadata(OLDER_RUN);
        writeMetadata(NEWER_RUN);

        assertThat(RunLookup.newest(storageConfig("tick-storage"), "tick-storage")).isEqualTo(NEWER_RUN);
    }

    @Test
    void theOrderOfWritingDoesNotDecideWhichRunIsNewest() throws Exception {
        writeMetadata(NEWER_RUN);
        writeMetadata(OLDER_RUN);

        assertThat(RunLookup.newest(storageConfig("tick-storage"), "tick-storage")).isEqualTo(NEWER_RUN);
    }

    @Test
    void aStorageWithoutRunsIsReportedAsSuch() {
        assertThatThrownBy(() -> RunLookup.newest(storageConfig("tick-storage"), "tick-storage"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("holds no simulation run")
            .hasMessageContaining("--run");
    }

    @Test
    void aNamedRunThatTheStorageHoldsIsAccepted() throws Exception {
        writeMetadata(OLDER_RUN);

        assertThat(RunLookup.existing(storageConfig("tick-storage"), "tick-storage", OLDER_RUN)).isEqualTo(OLDER_RUN);
    }

    @Test
    void aNamedRunThatTheStorageDoesNotHoldIsReportedAsSuch() throws Exception {
        writeMetadata(OLDER_RUN);

        assertThatThrownBy(() -> RunLookup.existing(storageConfig("tick-storage"), "tick-storage", "20260101-00000000-typo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("20260101-00000000-typo")
            .hasMessageContaining("tick-storage");
    }

    @Test
    void aStorageNameThatIsNotConfiguredIsReportedAsSuch() {
        assertThatThrownBy(() -> RunLookup.newest(storageConfig("tick-storage"), "no-such-storage"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no-such-storage");
    }

    /**
     * Builds a configuration that holds one file system storage resource pointing at the
     * temporary directory of this test.
     *
     * @param name the name the resource carries under {@code pipeline.resources}
     * @return the configuration a lookup reads the resource from
     */
    private Config storageConfig(final String name) {
        return ConfigFactory.parseString("""
            pipeline.resources."%s" {
              className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
              options.rootDirectory = %s
            }
            """.formatted(name, ConfigUtil.quoteString(storageDir.toAbsolutePath().toString())));
    }

    /**
     * Writes the metadata file that makes a run visible in storage.
     *
     * @param runId the run to write
     * @throws IOException if the metadata cannot be written
     */
    private void writeMetadata(final String runId) throws IOException {
        Files.createDirectories(storageDir);
        FileSystemStorageResource storage = new FileSystemStorageResource("tick-storage",
            ConfigFactory.parseString("rootDirectory = " + ConfigUtil.quoteString(storageDir.toAbsolutePath().toString())));
        storage.writeMessage(runId + "/raw/metadata.pb",
            SimulationMetadata.newBuilder().setSimulationRunId(runId).build());
    }
}
