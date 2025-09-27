package org.evochora.datapipeline.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.cli.commands.*;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "evochora", mixinStandardHelpOptions = true,
        version = "EvoChora Data Pipeline 1.0",
        description = "Manages the EvoChora Data Pipeline.",
        subcommands = {
                StartCommand.class,
                StopCommand.class,
                RestartCommand.class,
                PauseCommand.class,
                ResumeCommand.class,
                StatusCommand.class,
                CompileCommand.class
        })
public class CommandLineInterface implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CommandLineInterface.class);

    @Option(names = {"-c", "--config"}, description = "Path to the configuration file.")
    private File configFile;

    @Option(names = {"-i", "--interactive"}, description = "Run in interactive mode.")
    private boolean interactive;

    private Config config;
    private ServiceManager serviceManager;
    private boolean loggingConfigured = false;
    private File loadedConfigFile;

    @picocli.CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        loadConfig();
        System.out.println("Using config: " + (loadedConfigFile != null ? loadedConfigFile.getAbsolutePath() : "defaults"));
        configureLogging();
        if (interactive) {
            runInteractiveShell();
        } else {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
        return 0;
    }

    private void runInteractiveShell() throws IOException {
        CommandLine cmd = new CommandLine(this);
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new SimpleCommandCompleter(cmd))
                    .build();

            String prompt = "evochora> ";
            while (true) {
                try {
                    String line = lineReader.readLine(prompt);
                    if (line == null || "exit".equalsIgnoreCase(line.trim()) || "quit".equalsIgnoreCase(line.trim())) {
                        break;
                    }
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    // Execute the command
                    cmd.execute(line.trim().split("\\s+"));
                } catch (UserInterruptException e) {
                    // Ignore Ctrl-C
                } catch (EndOfFileException e) {
                    // Ctrl-D, exit
                    return;
                }
            }
        }
    }

    private static class SimpleCommandCompleter implements Completer {
        private final CommandLine cmd;

        public SimpleCommandCompleter(CommandLine cmd) {
            this.cmd = cmd;
        }

        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            if (line.wordIndex() == 0) {
                String word = line.word();
                for (String commandName : cmd.getSubcommands().keySet()) {
                    if (commandName.startsWith(word)) {
                        candidates.add(new Candidate(commandName));
                    }
                }
            }
        }
    }


    private void configureLogging() {
        if (loggingConfigured) return;

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        if (!config.hasPath("logging")) {
            loggingConfigured = true;
            return; // No logging configuration found, use logback's default
        }

        Config loggingConfig = config.getConfig("logging");

        // Determine mode and apply mode-specific overrides
        String modeConfigPath = interactive ? "interactive" : "headless";
        if (loggingConfig.hasPath(modeConfigPath)) {
            loggingConfig = loggingConfig.getConfig(modeConfigPath).withFallback(loggingConfig);
        }

        // Set appender format (JSON or PLAIN)
        String format = loggingConfig.hasPath("format") ? loggingConfig.getString("format") : "JSON";
        if ("PLAIN".equalsIgnoreCase(format)) {
            context.putProperty("evochora.logging.format", "STDOUT_PLAIN");
        } else {
            context.putProperty("evochora.logging.format", "STDOUT");
        }

        // Set root log level
        if (loggingConfig.hasPath("level")) {
            String rootLevelStr = loggingConfig.getString("level");
            ch.qos.logback.classic.Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(Level.toLevel(rootLevelStr, Level.WARN));
        }

        // Set specific logger levels
        if (loggingConfig.hasPath("loggers")) {
            Config loggersConfig = loggingConfig.getConfig("loggers");
            for (Map.Entry<String, com.typesafe.config.ConfigValue> entry : loggersConfig.root().entrySet()) {
                String loggerName = entry.getKey();
                String levelName = entry.getValue().unwrapped().toString();
                ch.qos.logback.classic.Logger logger = context.getLogger(loggerName);
                logger.setLevel(Level.toLevel(levelName));
            }
        }
        loggingConfigured = true;
    }

    private void loadConfig() {
        if (config != null) {
            return; // Already loaded
        }

        // 1. Load the default configuration from 'reference.conf' in the JAR file.
        // This is the lowest priority level.
        Config referenceConfig = ConfigFactory.load();

        // 2. Determine the user config file to load: either the one specified via --config
        // or default to 'evochora.conf' in the current directory.
        File userConfigFile = configFile; // 'configFile' is the field set by Picocli
        if (userConfigFile == null) {
            userConfigFile = new File("evochora.conf").getAbsoluteFile();
        }

        // 3. Load the user config file if it exists.
        Config fileConfig;
        if (userConfigFile.exists()) {
            log.info("Loading configuration from {}", userConfigFile.getAbsolutePath());
            fileConfig = ConfigFactory.parseFile(userConfigFile);
            loadedConfigFile = userConfigFile;
        } else {
            // Issue a warning if an explicit file was not found.
            if (configFile != null) {
                log.warn("Configuration file not found at {}", configFile.getAbsolutePath());
            }
            fileConfig = ConfigFactory.empty();
            loadedConfigFile = null;
        }

        // 4. Parse environment variables (this logic was already correct).
        final String prefix = "EVOCHORA_";
        Map<String, Object> mappedEnv = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String key = entry.getKey().substring(prefix.length()).toLowerCase().replace('_', '.');
                if (key.contains("..") || key.startsWith(".") || key.endsWith(".") || key.isEmpty()) {
                    continue;
                }
                mappedEnv.put(key, entry.getValue());
            }
        }
        Config envConfig = ConfigFactory.parseMap(mappedEnv);

        // 5. Combine all configurations in the correct order and resolve variables.
        // Priority: Environment variables > User config file > reference.conf
        config = envConfig.withFallback(fileConfig).withFallback(referenceConfig).resolve();
    }


    public ServiceManager getServiceManager() {
        if (serviceManager == null) {
            loadConfig();
            configureLogging();
            serviceManager = new ServiceManager(config);
        }
        return serviceManager;
    }

    // New constructor for testing with a mock ServiceManager
    public CommandLineInterface(ServiceManager serviceManager) {
        this.serviceManager = serviceManager;
        this.loggingConfigured = true; // Assume logging is handled by the test setup
    }

    public CommandLineInterface() {
        // Default constructor for production
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            exitCode = new CommandLine(new CommandLineInterface()).execute(args);
        } catch (Exception e) {
            // This is a fallback for exceptions not caught by picocli
            System.err.println("An unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}