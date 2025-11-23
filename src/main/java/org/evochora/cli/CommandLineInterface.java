package org.evochora.cli;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.cli.commands.CompileCommand;
import org.evochora.cli.commands.InspectCommand;
import org.evochora.cli.commands.RenderVideoCommand;
import org.evochora.cli.commands.node.NodeCommand;
import org.evochora.cli.config.LoggingConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

@Command(
    name = "evochora",
    mixinStandardHelpOptions = true,
    version = "Evochora 1.0",
    description = "Evochora - Advanced Evolution Simulation Platform",
    subcommands = {
        NodeCommand.class,
        CompileCommand.class,
        InspectCommand.class,
        RenderVideoCommand.class,
        CommandLine.HelpCommand.class
    }
)
public class CommandLineInterface implements Callable<Integer> {

    private static final String CONFIG_FILE_NAME = "evochora.conf";

    @Option(
        names = {"-c", "--config"},
        description = "Path to custom configuration file (default: evochora.conf)"
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
        final CommandLine commandLine = new CommandLine(new CommandLineInterface());
        commandLine.setCommandName("evochora");
        final int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    private void initialize() {
        if (initialized) {
            return;
        }

        // Configuration loading
        final File confFile = (this.configFile != null) ? this.configFile : new File(CONFIG_FILE_NAME);
        try {
            if (confFile.exists()) {
                this.config = ConfigFactory.parseFile(confFile).withFallback(ConfigFactory.load()).resolve();
            } else {
                this.config = ConfigFactory.load().resolve();
            }
        } catch (com.typesafe.config.ConfigException e) {
            // Log config error and exit gracefully
            final Logger errorLogger = LoggerFactory.getLogger(CommandLineInterface.class);
            errorLogger.error("Failed to load configuration from {}: {}", confFile.getAbsolutePath(), e.getMessage());
            System.exit(1);
            return; // Unreachable, but satisfies compiler
        }

        // Logging setup
        if (config.hasPath("logging.format")) {
            final String format = config.getString("logging.format");
            System.setProperty("evochora.logging.format", "PLAIN".equalsIgnoreCase(format) ? "STDOUT_PLAIN" : "STDOUT");
            reconfigureLogback();
        }
        LoggingConfigurator.configure(config);

        final Logger logger = LoggerFactory.getLogger(CommandLineInterface.class);

        // Welcome message and config logging - only for node commands
        if (config.hasPath("node.show-welcome-message") && config.getBoolean("node.show-welcome-message")) {
            showWelcomeMessage();
        }
        if (confFile.exists()) {
            logger.info("Using configuration file: {}", confFile.getAbsolutePath());
        } else {
            logger.info("Using default configuration from classpath (file not found: {})", confFile.getAbsolutePath());
        }

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

    private void showWelcomeMessage() {
        System.out.println("\nWelcome to...\n" +
                "  ■■■■■  ■   ■   ■■■    ■■■   ■   ■   ■■■   ■■■■     ■   \n" +
                "  ■      ■   ■  ■   ■  ■   ■  ■   ■  ■   ■  ■   ■   ■ ■  \n" +
                "  ■      ■   ■  ■   ■  ■      ■   ■  ■   ■  ■   ■  ■   ■ \n" +
                "  ■■■■    ■ ■   ■   ■  ■      ■■■■■  ■   ■  ■■■■   ■   ■ \n" +
                "  ■       ■ ■   ■   ■  ■      ■   ■  ■   ■  ■ ■    ■■■■■ \n" +
                "  ■       ■ ■   ■   ■  ■   ■  ■   ■  ■   ■  ■  ■   ■   ■ \n" +
                "  ■■■■■    ■     ■■■    ■■■   ■   ■   ■■■   ■   ■  ■   ■ \n" +
                "Advanced Scientific Evolution Research Simulation Platform\n");
            //"  ________      ______   _____ _    _  ____  _____            \n" +
            //" |  ____\\ \\    / / __ \\ / ____| |  | |/ __ \\|  __ \\     /\\    \n" +
            //" | |__   \\ \\  / / |  | | |    | |__| | |  | | |__) |   /  \\   \n" +
            //" |  __|   \\ \\/ /| |  | | |    |  __  | |  | |  _  /   / /\\ \\  \n" +
            //" | |____   \\  / | |__| | |____| |  | | |__| | | \\ \\  / ____ \\ \n" +
            //" |______|   \\/   \\____/ \\_____|_|  |_|\\____/|_|  \\_\\/_/    \\_\\\n\n" +
            //"            Advanced Evolution Simulation Platform\n");
    }

    public Config getConfig() {
        if (!initialized) {
            initialize();
        }
        return config;
    }
}