package org.evochora.cli;

import org.evochora.BuildInfo;
import org.evochora.cli.config.ConfigLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import com.typesafe.config.ConfigFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
public class CommandLineInterfaceTest {

    @Test
    public void testCliInitialization() {
        CommandLineInterface cli = new CommandLineInterface();
        CommandLine cmd = new CommandLine(cli);
        assertEquals("evochora", cmd.getCommandName());
    }

    @Test
    public void aSubcommandAnswersVersionWithTheBuildsVersion() {
        CommandLine cmd = CommandLineInterface.createCommandLine();
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("node", "run", "--version");

        assertEquals(0, exitCode);
        assertEquals("Evochora " + BuildInfo.version() + " (" + BuildInfo.revision() + ")", out.toString().strip());
    }

    /**
     * The project folder and the configuration file are named in the log once the logging of the
     * configuration applies; before that only WARN and above would pass.
     */
    @Test
    public void theProjectFolderAndTheConfigurationFileAreLoggedOnce(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("experiment.conf");
        // The default level is left to logback-test.xml in tests; a level for this logger is applied
        Files.writeString(file, "logging { format = \"PLAIN\", levels { \"org.evochora.cli.CommandLineInterface\" = \"INFO\" } }\n");
        CommandLine cmd = CommandLineInterface.createCommandLine();
        cmd.parseArgs("--config", file.toString());
        CommandLineInterface cli = cmd.getCommand();
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setProperty(ConfigLoader.HOME_KEY, dir.toString());
        ConfigFactory.invalidateCaches();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            cli.getConfig();
            cli.getConfig(ConfigFactory.empty());
        } finally {
            System.setErr(original);
            System.clearProperty(ConfigLoader.HOME_KEY);
            ConfigFactory.invalidateCaches();
        }

        String log = captured.toString(StandardCharsets.UTF_8);
        String folderLine = "Project folder: " + dir.toFile().getAbsolutePath() + ", taken from -Devochora.home";
        assertTrue(log.contains(folderLine), log);
        assertTrue(log.contains("Using configuration file specified via --config: " + file.toFile().getAbsolutePath()), log);
        assertEquals(log.indexOf(folderLine), log.lastIndexOf(folderLine), "named once: " + log);
    }
}
