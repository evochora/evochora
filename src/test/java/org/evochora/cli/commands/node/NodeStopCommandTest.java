package org.evochora.cli.commands.node;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class NodeStopCommandTest {

    @Test
    public void testNodeStopCommand() {
        // This is a basic test to ensure the command can be instantiated.
        // A more comprehensive test would require mocking the HTTP client and server.
        NodeStopCommand stopCommand = new NodeStopCommand();
        CommandLine cmd = new CommandLine(stopCommand);
        assertDoesNotThrow(() -> cmd.parseArgs());
    }
}