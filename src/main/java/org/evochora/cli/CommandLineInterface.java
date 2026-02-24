package org.evochora.cli;

import java.io.File;
import java.util.concurrent.Callable;

import org.evochora.cli.commands.CleanupCommand;
import org.evochora.cli.commands.CompileCommand;
import org.evochora.cli.commands.InspectCommand;
import org.evochora.cli.commands.RenderVideoCommand;
import org.evochora.cli.commands.node.NodeCommand;
import org.evochora.cli.config.LoggingConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

import org.evochora.cli.config.ConfigLoader;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "evochora",
    mixinStandardHelpOptions = true,
    version = "Evochora 1.0",
    description = "Evochora - Simulation Platform for Digital Evolution Research",
    subcommands = {
        NodeCommand.class,
        CompileCommand.class,
        InspectCommand.class,
        RenderVideoCommand.class,
        CleanupCommand.class,
        CommandLine.HelpCommand.class
    },
    footer = {
        "",
        "JVM Options:",
        "  Default heap size is 8 GB. To adjust, set the EVOCHORA_OPTS or JAVA_OPTS",
        "  environment variable before starting:",
        "",
        "    EVOCHORA_OPTS=\"-Xmx16g\" bin/evochora node run"
    }
)
public class CommandLineInterface implements Callable<Integer> {

    @Option(
        names = {"-c", "--config"},
        description = "Path to custom configuration file (default: config/evochora.conf)"
    )
    private File configFile;

    private Config config;
    private boolean initialized = false;

    @Override
    public Integer call() {
        // If no subcommand is specified, show the help message.
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(final String[] args) {
        final CommandLine commandLine = createCommandLine();
        final int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    /**
     * Creates a fully configured CommandLine instance.
     * <p>
     * Use this method in tests to get the same configuration as the CLI entry point.
     *
     * @return A configured CommandLine instance.
     */
    public static CommandLine createCommandLine() {
        final CommandLine commandLine = new CommandLine(new CommandLineInterface());
        commandLine.setCommandName("evochora");
        return commandLine;
    }

    private void initialize() {
        if (initialized) {
            return;
        }

        // Initialize logger early for config loading feedback
        final Logger logger = LoggerFactory.getLogger(CommandLineInterface.class);

        try {
            this.config = ConfigLoader.resolve(this.configFile, (level, message) -> {
                switch (level) {
                    case INFO -> logger.info(message);
                    case WARN -> logger.warn(message);
                }
            });
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage());
            System.exit(1);
        } catch (com.typesafe.config.ConfigException e) {
            logger.error("Failed to load or parse configuration: {}", e.getMessage());
            System.exit(1);
        }

        // Logging setup
        if (config.hasPath("logging.format")) {
            final String format = config.getString("logging.format");
            System.setProperty("evochora.logging.format", "PLAIN".equalsIgnoreCase(format) ? "STDOUT_PLAIN" : "STDOUT");
            reconfigureLogback();
        }
        LoggingConfigurator.configure(config);

        // Welcome message logic moved to NodeRunCommand
        // (Only relevant for long-running node process)

        initialized = true;
    }


    private void reconfigureLogback() {
        try {
            ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            context.reset();
            java.net.URL configUrl = CommandLineInterface.class.getClassLoader().getResource("logback.xml");
            if (configUrl != null) {
                configurator.doConfigure(configUrl);
            }
        } catch (Exception e) {
            System.err.println("Failed to reconfigure Logback: " + e.getMessage());
        }
    }

    public void showWelcomeMessage() {
        // Check config again just to be safe, though caller usually checks too
        if (config.hasPath("node.show-welcome-message") && config.getBoolean("node.show-welcome-message")) {
            String logFormat = config.hasPath("logging.format") ? config.getString("logging.format") : "PLAIN";
            if (!"PLAIN".equalsIgnoreCase(logFormat)) {
                return;
            }
            
            System.out.println("\nWelcome to...\n" +
                "  ■■■■■  ■   ■   ■■■    ■■■   ■   ■   ■■■   ■■■■     ■   \n" +
                "  ■      ■   ■  ■   ■  ■   ■  ■   ■  ■   ■  ■   ■   ■ ■  \n" +
                "  ■      ■   ■  ■   ■  ■      ■   ■  ■   ■  ■   ■  ■   ■ \n" +
                "  ■■■■    ■ ■   ■   ■  ■      ■■■■■  ■   ■  ■■■■   ■   ■ \n" +
                "  ■       ■ ■   ■   ■  ■      ■   ■  ■   ■  ■ ■    ■■■■■ \n" +
                "  ■       ■ ■   ■   ■  ■   ■  ■   ■  ■   ■  ■  ■   ■   ■ \n" +
                "  ■■■■■    ■     ■■■    ■■■   ■   ■   ■■■   ■   ■  ■   ■ \n" +
                "    Simulation Platform for Digital Evolution Research\n");
            //"  ________      ______   _____ _    _  ____  _____            \n" +
            //" |  ____\\ \\    / / __ \\ / ____| |  | |/ __ \\|  __ \\     /\\    \n" +
            //" | |__   \\ \\  / / |  | | |    | |__| | |  | | |__) |   /  \\   \n" +
            //" |  __|   \\ \\/ /| |  | | |    |  __  | |  | |  _  /   / /\\ \\  \n" +
            //" | |____   \\  / | |__| | |____| |  | | |__| | | \\ \\  / ____ \\ \n" +
            //" |______|   \\/   \\____/ \\_____|_|  |_|\\____/|_|  \\_\\/_/    \\_\\\n\n" +
            //"            Advanced Evolution Simulation Platform\n");
        }
    }

    public Config getConfig() {
        if (!initialized) {
            initialize();
        }
        return config;
    }
}