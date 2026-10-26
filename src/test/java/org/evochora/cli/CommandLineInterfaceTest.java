package org.evochora.cli;

import org.evochora.BuildInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
