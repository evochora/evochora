package org.evochora.cli.commands;

import org.evochora.cli.CommandLineInterface;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "inspect",
    description = "Inspect simulation data and storage",
    subcommands = {
        InspectStorageSubcommand.class
    }
)
public class InspectCommand {
    @ParentCommand
    private CommandLineInterface parent;

    public CommandLineInterface getParent() {
        return parent;
    }
}
