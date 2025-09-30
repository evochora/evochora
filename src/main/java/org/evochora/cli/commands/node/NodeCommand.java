package org.evochora.cli.commands.node;

import org.evochora.cli.CommandLineInterface;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "node",
    description = "Manages the Evochora Node server",
    subcommands = {
        NodeRunCommand.class
    }
)
public class NodeCommand {
    @ParentCommand
    private CommandLineInterface parent;

    public CommandLineInterface getParent() {
        return parent;
    }
}