package org.evochora.cli.commands.node;

import org.evochora.cli.CommandLineInterface;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
 * The {@code node} subcommand: a container that groups the commands managing the Evochora node
 * server. It carries out no work of its own and exists to make the root command — and with it the
 * configuration of the invocation — reachable for its subcommands.
 * <p>
 * Invoked without a subcommand it reports the missing subcommand, prints the usage text listing
 * the ones it has, and exits with code 2. That differs from the root command, which prints its
 * usage and exits with 0.
 */
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

    /**
     * Returns the root command of the running invocation, through which subcommands reach the
     * configuration selected on the command line. The reference is injected while the command line
     * is parsed and is therefore {@code null} before then.
     *
     * @return the root command this subcommand was invoked from
     */
    public CommandLineInterface getParent() {
        return parent;
    }
}