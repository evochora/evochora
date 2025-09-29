package org.evochora.cli.commands.node;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class NodeRunCommandTest {

    @Test
    public void testNodeRunCommand() {
        // This is a basic test to ensure the command can be instantiated.
        // A more comprehensive test would require mocking the Node and its dependencies.
        NodeRunCommand runCommand = new NodeRunCommand();
        CommandLine cmd = new CommandLine(runCommand);
        assertDoesNotThrow(() -> cmd.parseArgs("-d"));
    }
}