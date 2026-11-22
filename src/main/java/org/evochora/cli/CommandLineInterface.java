package org.evochora.cli;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.typesafe.config.ConfigFactory;

import org.evochora.cli.config.ConfigLoader;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Root command of the {@code evochora} command line tool. Invoked without a subcommand it prints
 * the usage text and returns exit code 0; all actual work is done by the registered subcommands.
 * <p>
 * The root command also owns the configuration of an invocation: the {@code --config} option names
 * the configuration file, and subcommands reach the resolved configuration through
 * {@link #getConfig()}. It is loaded on first access and kept afterwards, so every subcommand of
 * one invocation sees the same values. A subcommand that has values of its own to place above the
 * configuration file — the node subcommands, which select a run, a tuning profile or the startup
 * behaviour from the command line — passes them to {@link #getConfig(Config)}, which resolves the
 * configuration again with those values on top and keeps that result in place of the first one.
 */
@Command(
    name = "evochora",
    mixinStandardHelpOptions = true,
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
        description = "Path to custom configuration file, relative to the working directory (default: config/evochora.conf of the project directory)"
    )
    private File configFile;

    private Config config;
    private boolean loggingConfigured = false;

    @Override
    public Integer call() {
        // If no subcommand is specified, show the help message.
        CommandLine.usage(this, System.out);
        return 0;
    }

    /**
     * Entry point of the command line tool. Parses the arguments, runs the selected subcommand and
     * then terminates the JVM with the exit code that subcommand returned, so this method never
     * returns to its caller.
     *
     * @param args the command line arguments in picocli syntax; an empty array prints the usage text
     */
    public static void main(final String[] args) {
        final CommandLine commandLine = createCommandLine();
        final int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    /**
     * Creates a fully configured CommandLine instance.
     * <p>
     * Use this method in tests to get the same configuration as the CLI entry point. Every command
     * answers {@code --version}, which its help options offer, with the version the build recorded.
     *
     * @return A configured CommandLine instance.
     */
    public static CommandLine createCommandLine() {
        final CommandLine commandLine = new CommandLine(new CommandLineInterface());
        commandLine.setCommandName("evochora");
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        answerVersion(commandLine, new BuildVersionProvider());
        return commandLine;
    }

    private static void answerVersion(final CommandLine command, final CommandLine.IVersionProvider provider) {
        command.getCommandSpec().versionProvider(provider);
        command.getSubcommands().values().forEach(subcommand -> answerVersion(subcommand, provider));
    }

    /**
     * Loads the configuration and then reports how it was found: the project directory and the
     * file. The loader's messages are held until the logging settings of the configuration
     * apply, because before that only the levels of {@code logback.xml} hold, which drop INFO;
     * they are reported on the first load only, since a reload with overrides finds the same
     * directory and file. A configuration that fails to load ends the process, and the error names
     * what the loader had found up to then.
     */
    private void initialize(final Config overrides) {
        final Logger logger = LoggerFactory.getLogger(CommandLineInterface.class);
        final List<Map.Entry<ConfigLoader.MessageLevel, String>> messages = new ArrayList<>();

        try {
            this.config = ConfigLoader.resolve(this.configFile, overrides,
                    (level, message) -> messages.add(Map.entry(level, message)));
        } catch (IllegalArgumentException e) {
            logger.error("{} [{}]", e.getMessage(), texts(messages));
            System.exit(1);
        } catch (com.typesafe.config.ConfigException e) {
            logger.error("Failed to load or parse configuration: {} [{}]", e.getMessage(), texts(messages));
            System.exit(1);
        }

        final boolean firstLoad = !loggingConfigured;
        configureLogging();
        if (firstLoad) {
            for (final Map.Entry<ConfigLoader.MessageLevel, String> message : messages) {
                switch (message.getKey()) {
                    case INFO -> logger.info(message.getValue());
                    case DEBUG -> logger.debug(message.getValue());
                    case WARN -> logger.warn(message.getValue());
                }
            }
        }
    }

    private static String texts(final List<Map.Entry<ConfigLoader.MessageLevel, String>> messages) {
        return String.join("; ", messages.stream().map(Map.Entry::getValue).toList());
    }

    /**
     * Applies the logging settings of the loaded configuration, at most once per invocation.
     * <p>
     * Reconfiguring Logback resets its context and drops every appender attached to it, so a
     * second pass would undo whatever the first one set up. A subcommand that reloads the
     * configuration with overrides therefore keeps the logging of the first load; the overrides
     * the node subcommands place select a run, a tuning profile and the startup behaviour, and
     * never touch {@code logging}.
     */
    private void configureLogging() {
        if (loggingConfigured) {
            return;
        }
        if (config.hasPath("logging.format")) {
            try {
                System.setProperty("evochora.logging.format",
                        LoggingConfigurator.appenderFor(config.getString("logging.format")));
            } catch (IllegalArgumentException e) {
                LoggerFactory.getLogger(CommandLineInterface.class).error(e.getMessage());
                System.exit(1);
            }
            reconfigureLogback();
        }
        LoggingConfigurator.configure(config);
        loggingConfigured = true;
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

    /**
     * Prints the ASCII art banner to standard output. The banner appears only when
     * {@code node.show-welcome-message} is present in the configuration and set to {@code true},
     * and only when {@code logging.format} is not {@code JSON}; in every other case the
     * method does nothing.
     * <p>
     * Reads the already loaded configuration and therefore requires a preceding
     * {@link #getConfig()} call.
     */
    public void showWelcomeMessage() {
        // Check config again just to be safe, though caller usually checks too
        if (config.hasPath("node.show-welcome-message") && config.getBoolean("node.show-welcome-message")) {
            if (config.hasPath("logging.format") && "JSON".equalsIgnoreCase(config.getString("logging.format"))) {
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

    /**
     * Returns the resolved configuration of this invocation. The first call loads it, either from
     * the file named by {@code --config} or, without that option, through the discovery cascade of
     * {@link ConfigLoader#resolve(File, ConfigLoader.ConfigMessageHandler)}, and applies the
     * logging settings it contains; later calls return the configuration that is in place, which
     * is the one a preceding {@link #getConfig(Config)} left behind.
     * <p>
     * A configuration file that is named but does not exist, and a file that cannot be parsed, are
     * not signalled by an exception: the error is logged and the JVM terminates with exit code 1.
     *
     * @return the resolved configuration for this invocation
     */
    public Config getConfig() {
        if (config == null) {
            initialize(ConfigFactory.empty());
        }
        return config;
    }

    /**
     * Reloads the configuration of this invocation with the given overrides on top of every other
     * source and returns it; later calls to {@link #getConfig()} return this configuration.
     * <p>
     * A subcommand that derives its overrides from the configuration itself — the name of a
     * storage resource to look a run up in, the names of the tuning profiles — reads the plain
     * configuration through {@link #getConfig()} first and hands the overrides it built to this
     * method afterwards. The configuration is resolved again from the same sources, so the
     * overrides take part in the substitutions rather than being written over a resolved result.
     *
     * @param overrides an unresolved configuration, as
     *                  {@link com.typesafe.config.ConfigFactory#parseString(String)} returns it,
     *                  placed above the system properties in every branch of the discovery cascade
     * @return the configuration of this invocation resolved with the overrides applied
     */
    public Config getConfig(final Config overrides) {
        initialize(overrides);
        return config;
    }
}