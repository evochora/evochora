package org.evochora.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CommandLineInterfaceTest {

    @Test
    public void testCliInitialization() {
        CommandLineInterface cli = new CommandLineInterface();
        CommandLine cmd = new CommandLine(cli);
        assertEquals("evochora", cmd.getCommandName());
    }
}