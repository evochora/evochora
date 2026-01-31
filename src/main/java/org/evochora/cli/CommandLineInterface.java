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
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
        CleanupCommand.class,
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
            // 1) Highest precedence: explicit CLI option --config
            if (this.configFile != null) {
                if (!this.configFile.exists()) {
                    logger.error("Configuration file specified via --config was not found: {}", this.configFile.getAbsolutePath());
                    System.exit(1);
                }
                logger.info("Using configuration file specified via --config: {}", this.configFile.getAbsolutePath());
                // Config load order: System Props > Env Vars > File > Classpath defaults
                this.config = ConfigFactory.systemProperties()
                        .withFallback(ConfigFactory.systemEnvironment())
                        .withFallback(ConfigFactory.parseFile(this.configFile))
                        .withFallback(ConfigFactory.load())
                        .resolve();
            } else {
                // 2) Next: standard Typesafe Config system property -Dconfig.file
                final String systemConfigPath = System.getProperty("config.file");
                if (systemConfigPath != null && !systemConfigPath.isBlank()) {
                    File systemConfigFile = new File(systemConfigPath);
                    if (!systemConfigFile.isAbsolute()) {
                        systemConfigFile = systemConfigFile.getAbsoluteFile();
                    }
                    if (!systemConfigFile.exists()) {
                        logger.error("Configuration file specified via -Dconfig.file was not found: {}", systemConfigFile.getAbsolutePath());
                        System.exit(1);
                    }
                    logger.info("Using configuration file specified via -Dconfig.file: {}", systemConfigFile.getAbsolutePath());
                    // Config load order: System Props > Env Vars > File > Classpath defaults
                    this.config = ConfigFactory.systemProperties()
                            .withFallback(ConfigFactory.systemEnvironment())
                            .withFallback(ConfigFactory.parseFile(systemConfigFile))
                            .withFallback(ConfigFactory.load())
                            .resolve();
                } else {
                    // 3) Then: evochora.conf in the current working directory
                    final File cwdConfigFile = new File(CONFIG_FILE_NAME);
                    if (cwdConfigFile.exists()) {
                        logger.info("Using configuration file found in current directory: {}", cwdConfigFile.getAbsolutePath());
                        this.config = ConfigFactory.systemProperties()
                                .withFallback(ConfigFactory.systemEnvironment())
                                .withFallback(ConfigFactory.parseFile(cwdConfigFile))
                                .withFallback(ConfigFactory.load())
                                .resolve();
                    } else {
                        // 4) Then: APP_HOME/config/evochora.conf inferred from the running JAR
                        final File installationConfigFile = detectInstallationConfigFile();
                        if (installationConfigFile != null && installationConfigFile.exists()) {
                            logger.info("Using configuration file from installation directory: {}", installationConfigFile.getAbsolutePath());
                            this.config = ConfigFactory.systemProperties()
                                    .withFallback(ConfigFactory.systemEnvironment())
                                    .withFallback(ConfigFactory.parseFile(installationConfigFile))
                                    .withFallback(ConfigFactory.load())
                                    .resolve();
                        } else {
                            // 5) Finally: fall back to classpath defaults only
                            logger.warn("No '{}' found in current directory or installation directory. Using default configuration from classpath.", CONFIG_FILE_NAME);
                            this.config = ConfigFactory.systemProperties()
                                    .withFallback(ConfigFactory.systemEnvironment())
                                    .withFallback(ConfigFactory.load())
                                    .resolve();
                        }
                    }
                }
            }
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

    /**
     * Attempts to detect the Evochora installation directory and its default configuration file.
     * <p>
     * The installation layout created by Gradle's {@code installDist} / {@code distZip} tasks is:
     * <pre>
     *   APP_HOME/
     *     bin/evochora
     *     lib/evochora-*.jar
     *     config/evochora.conf
     * </pre>
     * This method infers {@code APP_HOME} from the location of the running JAR (or classes
     * directory during development) and returns {@code APP_HOME/config/evochora.conf} if it exists.
     *
     * @return the detected configuration file in the installation directory, or {@code null} if it
     * cannot be determined or does not exist.
     */
    private File detectInstallationConfigFile() {
        try {
            final java.security.ProtectionDomain protectionDomain = CommandLineInterface.class.getProtectionDomain();
            if (protectionDomain == null) {
                return null;
            }
            final java.security.CodeSource codeSource = protectionDomain.getCodeSource();
            if (codeSource == null) {
                return null;
            }
            final java.net.URL location = codeSource.getLocation();
            final File jarOrClasses = new File(location.toURI());

            final File appHome;
            if (jarOrClasses.isFile()) {
                // Running from the application JAR under APP_HOME/lib
                final File libDir = jarOrClasses.getParentFile();
                if (libDir == null) {
                    return null;
                }
                appHome = libDir.getParentFile();
            } else {
                // Running from classes directory during development
                appHome = jarOrClasses;
            }

            if (appHome == null) {
                return null;
            }

            final File configDir = new File(appHome, "config");
            final File configFile = new File(configDir, CONFIG_FILE_NAME);
            return configFile.exists() ? configFile : null;
        } catch (Exception ignored) {
            // Best-effort detection; fall back to other mechanisms on any failure.
            return null;
        }
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
                "   Simulator for Foundational Artificial Life Research\n");
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