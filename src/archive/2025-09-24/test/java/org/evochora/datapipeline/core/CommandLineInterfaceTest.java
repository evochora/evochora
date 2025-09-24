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
        // Test that default configuration loads without errors
        assertThat(config).isNotNull();
        // Test that we can access some basic configuration
        assertThat(config.hasPath("pipeline")).isTrue();
    }

    @Test
    void testFileConfiguration(@TempDir Path tempDir) throws IOException {
        File configFile = tempDir.resolve("test.conf").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("pipeline.channels.test-channel.options.capacity = 5000\n");
            writer.write("pipeline.channels.test-channel.className = \"org.evochora.datapipeline.channels.InMemoryChannel\"\n");
        }

        cli.configFile = configFile;
        Config config = cli.loadConfiguration();

        assertThat(config.getInt("pipeline.channels.test-channel.options.capacity"))
                .isEqualTo(5000);
        assertThat(config.getString("pipeline.channels.test-channel.className"))
                .isEqualTo("org.evochora.datapipeline.channels.InMemoryChannel");
    }

    @Test
    void testCliOverride(@TempDir Path tempDir) throws IOException {
        File configFile = tempDir.resolve("test.conf").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("pipeline.channels.test-channel.options.capacity = 5000\n");
            writer.write("pipeline.channels.test-channel.className = \"org.evochora.datapipeline.channels.InMemoryChannel\"\n");
        }

        cli.configFile = configFile;
        cli.hoconOverrides = java.util.Map.of("pipeline.channels.test-channel.options.capacity", "9999");
        Config config = cli.loadConfiguration();

        assertThat(config.getInt("pipeline.channels.test-channel.options.capacity"))
                .isEqualTo(9999);
    }
}
