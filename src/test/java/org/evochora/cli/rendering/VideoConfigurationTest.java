package org.evochora.cli.rendering;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.evochora.cli.CommandLineInterface;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The video commands read the configuration the root command's {@code --config} names, like every
 * other command.
 */
@Tag("integration")
class VideoConfigurationTest {

    /**
     * The storage the renderer is pointed at exists only in the given file: had the renderer read
     * any other configuration, it would report the storage as not configured instead of finding no
     * run in it.
     */
    @Test
    void aVideoCommandReadsTheConfigurationTheRootCommandNames(@TempDir Path dir) throws Exception {
        Path storage = Files.createDirectory(dir.resolve("storage"));
        Path config = dir.resolve("video.conf");
        Files.writeString(config, """
            pipeline.resources.video-test-storage {
              className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
              options { rootDirectory = "%s" }
            }
            """.formatted(storage.toString().replace("\\", "/")));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exitCode;
        try {
            exitCode = CommandLineInterface.createCommandLine()
                .execute("-c", config.toString(), "video", "exact", "--storage", "video-test-storage");
        } finally {
            System.setErr(originalErr);
        }

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("No simulation runs found.")
            .doesNotContain("not configured");
    }
}
