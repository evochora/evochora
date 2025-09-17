package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommandLineInterfaceTest {

    private CommandLineInterface cli;

    @BeforeEach
    void setUp() {
        cli = new CommandLineInterface();
    }

    @Test
    void testDefaultConfiguration() {
        Config config = cli.loadConfiguration();
        assertThat(config.getString("pipeline.channels.raw-tick-stream.className"))
                .isEqualTo("org.evochora.datapipeline.channels.InMemoryChannel");
        assertThat(config.getInt("pipeline.channels.raw-tick-stream.options.capacity"))
                .isEqualTo(1000);
    }

    @Test
    void testFileConfiguration(@TempDir Path tempDir) throws IOException {
        File configFile = tempDir.resolve("test.conf").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("pipeline.channels.raw-tick-stream.options.capacity = 5000");
        }

        cli.configFile = configFile;
        Config config = cli.loadConfiguration();

        assertThat(config.getInt("pipeline.channels.raw-tick-stream.options.capacity"))
                .isEqualTo(5000);
        // Verify that it falls back to the default for other values
        assertThat(config.getString("pipeline.channels.raw-tick-stream.className"))
                .isEqualTo("org.evochora.datapipeline.channels.InMemoryChannel");
    }

    @Test
    void testCliOverride(@TempDir Path tempDir) throws IOException {
        File configFile = tempDir.resolve("test.conf").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("pipeline.channels.raw-tick-stream.options.capacity = 5000");
        }

        cli.configFile = configFile;
        cli.hoconOverrides = java.util.Map.of("pipeline.channels.raw-tick-stream.options.capacity", "9999");
        Config config = cli.loadConfiguration();

        assertThat(config.getInt("pipeline.channels.raw-tick-stream.options.capacity"))
                .isEqualTo(9999);
    }
}
