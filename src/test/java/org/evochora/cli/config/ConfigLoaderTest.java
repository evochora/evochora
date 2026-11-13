package org.evochora.cli.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfigLoader} to verify the configuration priority hierarchy:
 * <ol>
 *   <li>System Properties (highest priority)</li>
 *   <li>Environment Variables</li>
 *   <li>Configuration File</li>
 *   <li>Default reference configuration (lowest priority)</li>
 * </ol>
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class ConfigLoaderTest {

    @BeforeEach
    void setUp() {
        // reference.conf resolves its paths against the project directory, which resolve() sets
        System.setProperty(ConfigLoader.HOME_KEY, System.getProperty("java.io.tmpdir"));
        ConfigFactory.invalidateCaches();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("test.value");
        System.clearProperty("test.priority");
        System.clearProperty("test.nested.setting");
        System.clearProperty("test.base-value");
        System.clearProperty("test.referenced-value");
        System.clearProperty(ConfigLoader.HOME_KEY);
        ConfigFactory.invalidateCaches();
    }

    @Test
    @DisplayName("loadFromFile should load configuration file with defaults when no overrides present")
    void loadFromFile_shouldLoadConfigFileWithDefaults() {
        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"));

        assertNotNull(config);
        assertTrue(config.hasPath("test.value"));
        assertEquals("file-value", config.getString("test.value"));
        assertEquals("file-priority", config.getString("test.priority"));
        assertEquals("file-nested", config.getString("test.nested.setting"));
    }

    @Test
    @DisplayName("System property should override file configuration")
    void loadFromFile_systemPropertyShouldOverrideFileConfig() {
        System.setProperty("test.value", "system-value");
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"));

        assertEquals("system-value", config.getString("test.value"));
        assertEquals("file-priority", config.getString("test.priority"));
    }

    @Test
    @DisplayName("System property should override nested configuration values")
    void loadFromFile_systemPropertyShouldOverrideNestedConfig() {
        System.setProperty("test.nested.setting", "system-nested");
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"));

        assertEquals("system-nested", config.getString("test.nested.setting"));
        assertEquals("file-value", config.getString("test.value"));
    }

    @Test
    @DisplayName("loadDefaults should return valid config without a config file")
    void loadDefaults_shouldReturnValidConfig() {
        Config config = ConfigLoader.loadDefaults();

        assertEquals(1, config.getInt("profiles.detailed.samplingInterval"),
                "The classpath defaults carry the profiles from reference.conf");
    }

    @Test
    @DisplayName("Should preserve configuration hierarchy order")
    void loadFromFile_shouldPreserveConfigurationHierarchyOrder() {
        System.setProperty("test.priority", "system-priority");
        System.setProperty("test.value", "system-value");
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"));

        assertEquals("system-priority", config.getString("test.priority"));
        assertEquals("system-value", config.getString("test.value"));
        assertEquals("file-nested", config.getString("test.nested.setting"));
    }

    @Test
    @DisplayName("Should resolve configuration references correctly")
    void loadFromFile_shouldResolveConfigurationReferences() {
        System.setProperty("test.priority", "system-override");
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.loadFromFile(testResource("references-config.conf"));

        assertEquals("base-suffix", config.getString("test.referenced-value"));
        assertEquals("system-override", config.getString("test.priority"));
    }

    @Test
    @DisplayName("Profile override should propagate through substitution chain to downstream services")
    void loadFromFile_profileOverrideShouldPropagateToServices() {
        Config userConfig = ConfigFactory.parseString("pipeline.tuning = ${profiles.sampled}");
        Config config = ConfigFactory.systemProperties()
                .withFallback(userConfig)
                .withFallback(ConfigFactory.defaultReferenceUnresolved())
                .resolve();

        // Read profile values as source of truth (not hardcoded — profile values may change)
        int profileSamplingInterval = config.getInt("profiles.sampled.samplingInterval");
        int profileInsertBatchSize = config.getInt("profiles.sampled.insertBatchSize");
        int profileFlushTimeoutMs = config.getInt("profiles.sampled.flushTimeoutMs");
        int profileMaxBatchSize = config.getInt("profiles.sampled.maxBatchSize");
        int profileBatchTimeoutSeconds = config.getInt("profiles.sampled.batchTimeoutSeconds");

        // Tuning level: profile values applied
        assertEquals(profileSamplingInterval, config.getInt("pipeline.tuning.samplingInterval"));
        assertEquals(profileInsertBatchSize, config.getInt("pipeline.tuning.insertBatchSize"));
        assertEquals(profileFlushTimeoutMs, config.getInt("pipeline.tuning.flushTimeoutMs"));

        // Downstream: simulation-engine picks up tuning via ${pipeline.tuning.xxx}
        assertEquals(profileSamplingInterval, config.getInt("pipeline.services.simulation-engine.options.samplingInterval"));

        // Downstream: persistence-service picks up tuning via ${pipeline.tuning.xxx}
        assertEquals(profileMaxBatchSize, config.getInt("pipeline.services.persistence-service-1.options.maxBatchSize"));
        assertEquals(profileBatchTimeoutSeconds, config.getInt("pipeline.services.persistence-service-1.options.batchTimeoutSeconds"));

        // Downstream: indexer picks up tuning via ${pipeline.tuning.xxx}
        assertEquals(profileInsertBatchSize, config.getInt("pipeline.services.environment-indexer-1.options.insertBatchSize"));
    }

    @Test
    @DisplayName("Override layer should win over the config file and the system properties")
    void loadFromFile_overrideShouldWinOverFileAndSystemProperty() {
        System.setProperty("test.value", "system-value");
        ConfigFactory.invalidateCaches();
        Config overrides = ConfigFactory.parseString("test.value = \"override-value\"");

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"), overrides);

        assertEquals("override-value", config.getString("test.value"));
        assertEquals("file-priority", config.getString("test.priority"));
    }

    @Test
    @DisplayName("Override of pipeline.runId should reach the simulation engine's resume.runId")
    void loadFromFile_runIdOverrideShouldReachResumeRunId() {
        Config overrides = ConfigFactory.parseString("pipeline.runId = \"20260101-12000000-abc\"");

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"), overrides);

        assertEquals("20260101-12000000-abc",
                config.getString("pipeline.services.simulation-engine.options.resume.runId"));
    }

    @Test
    @DisplayName("Profile substitution in the override layer should reach downstream services")
    void loadFromFile_profileSubstitutionInOverrideShouldReachServices() {
        Config overrides = ConfigFactory.parseString("pipeline.tuning = ${profiles.sampled}");

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"), overrides);

        int profileSamplingInterval = config.getInt("profiles.sampled.samplingInterval");
        assertEquals(profileSamplingInterval, config.getInt("pipeline.tuning.samplingInterval"));
        assertEquals(profileSamplingInterval,
                config.getInt("pipeline.services.simulation-engine.options.samplingInterval"));
    }

    @Test
    @DisplayName("Sampling interval set on top of a profile should win over the profile's value")
    void loadFromFile_samplingIntervalShouldWinOverProfileInSameOverrideDocument() {
        Config overrides = ConfigFactory.parseString("""
                pipeline.tuning = ${profiles.sampled}
                pipeline.tuning.samplingInterval = 7
                """);

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"), overrides);

        assertEquals(7, config.getInt("pipeline.tuning.samplingInterval"));
        assertEquals(7, config.getInt("pipeline.services.simulation-engine.options.samplingInterval"));
        // The rest of the profile still applies
        assertEquals(config.getInt("profiles.sampled.snapshotInterval"),
                config.getInt("pipeline.tuning.snapshotInterval"));
    }

    @Test
    @DisplayName("Sampling interval alone should win over the configured tuning profile")
    void loadFromFile_samplingIntervalWithoutProfileShouldWinOverConfiguredProfile() {
        Config overrides = ConfigFactory.parseString("pipeline.tuning.samplingInterval = 13");

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"), overrides);

        assertEquals(13, config.getInt("pipeline.tuning.samplingInterval"));
        assertEquals(13, config.getInt("pipeline.services.simulation-engine.options.samplingInterval"));
    }

    @Test
    @DisplayName("loadDefaults should place the override layer above the system properties")
    void loadDefaults_overrideShouldWinOverSystemProperty() {
        System.setProperty("test.value", "system-value");
        ConfigFactory.invalidateCaches();
        Config overrides = ConfigFactory.parseString("test.value = \"override-value\"");

        Config config = ConfigLoader.loadDefaults(overrides);

        assertEquals("override-value", config.getString("test.value"));
    }

    @Test
    @DisplayName("The system property evochora.home names the project directory")
    void detectProjectDirectory_systemPropertyWins(@TempDir Path dir) throws IOException {
        File jar = jarIn(dir.resolve("install").resolve("lib"));

        ConfigLoader.ProjectDirectory folder = ConfigLoader.detectProjectDirectory(
                dir.resolve("given").toString(), jar, dir.toFile());

        assertEquals(dir.resolve("given").toFile().getAbsoluteFile(), folder.directory());
        assertEquals("-Devochora.home", folder.source());
    }

    @Test
    @DisplayName("A JAR in lib/ makes the directory above it the project directory")
    void detectProjectDirectory_jarInLibIsAnInstallation(@TempDir Path dir) throws IOException {
        File jar = jarIn(dir.resolve("install").resolve("lib"));

        ConfigLoader.ProjectDirectory folder = ConfigLoader.detectProjectDirectory(null, jar, dir.resolve("elsewhere").toFile());

        assertEquals(dir.resolve("install").toFile().getAbsoluteFile(), folder.directory());
        assertEquals("the installation", folder.source());
    }

    @Test
    @DisplayName("A JAR outside lib/ belongs to no installation: the working directory is the project directory")
    void detectProjectDirectory_jarOutsideLibFallsBackToWorkingDirectory(@TempDir Path dir) throws IOException {
        File jar = jarIn(dir.resolve("build").resolve("libs"));

        ConfigLoader.ProjectDirectory folder = ConfigLoader.detectProjectDirectory(null, jar, dir.toFile());

        assertEquals(dir.toFile().getAbsoluteFile(), folder.directory());
        assertEquals("the working directory", folder.source());
    }

    @Test
    @DisplayName("Classes run from a build directory fall back to the working directory")
    void detectProjectDirectory_classesDirectoryFallsBackToWorkingDirectory(@TempDir Path dir) throws IOException {
        File classes = Files.createDirectories(dir.resolve("build").resolve("classes")).toFile();

        assertEquals(dir.toFile().getAbsoluteFile(),
                ConfigLoader.detectProjectDirectory(null, classes, dir.toFile()).directory());
        assertEquals(dir.toFile().getAbsoluteFile(),
                ConfigLoader.detectProjectDirectory(null, null, dir.toFile()).directory());
    }

    @Test
    @DisplayName("resolve takes config/evochora.conf of the project directory, puts the folder into evochora.home and names it")
    void resolve_takesTheConfigurationOfTheProjectDirectory(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config").resolve("evochora.conf"), "test.value = \"project-value\"\n");
        System.setProperty(ConfigLoader.HOME_KEY, dir.toString());
        ConfigFactory.invalidateCaches();
        List<String> messages = new ArrayList<>();

        Config config = ConfigLoader.resolve(null, (level, message) -> messages.add(message));

        assertEquals("project-value", config.getString("test.value"));
        assertEquals(dir.toFile().getAbsolutePath(), config.getString(ConfigLoader.HOME_KEY));
        assertTrue(messages.contains("Project: " + dir.toFile().getAbsolutePath()), "Messages: " + messages);
        assertTrue(messages.contains("Project directory taken from -Devochora.home"), "Messages: " + messages);
    }

    @Test
    @DisplayName("An explicit configuration file gets evochora.home as well")
    void resolve_explicitFileGetsTheProjectDirectory(@TempDir Path dir) {
        System.setProperty(ConfigLoader.HOME_KEY, dir.toString());
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.resolve(testResource("test-config.conf"), (level, message) -> { });

        assertEquals("file-value", config.getString("test.value"));
        assertEquals(dir.toFile().getAbsolutePath(), config.getString(ConfigLoader.HOME_KEY));
    }

    @Test
    @DisplayName("project-directory set in a file moves the data and the base directory of the source roots with it")
    void resolve_projectDirectoryMovesWhatDerivesFromIt(@TempDir Path dir) throws IOException {
        Path elsewhere = dir.resolve("elsewhere");
        Path file = dir.resolve("experiment.conf");
        Files.writeString(file, "project-directory = \"" + elsewhere.toString().replace("\\", "/") + "\"\n");
        System.setProperty(ConfigLoader.HOME_KEY, dir.toString());
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.resolve(file.toFile(), (level, message) -> { });

        String moved = elsewhere.toString().replace("\\", "/");
        assertEquals(moved + "/data", config.getString("pipeline.dataBaseDir"));
        assertEquals(moved, config.getString("pipeline.services.simulation-engine.options.compiler.base-directory"));
    }

    private static File jarIn(Path directory) throws IOException {
        Files.createDirectories(directory);
        return Files.createFile(directory.resolve("evochora.jar")).toFile();
    }

    /**
     * Locates a test resource file on the classpath.
     *
     * @param name the resource file name (relative to this test class's package).
     * @return the {@link File} pointing to the test resource.
     */
    private File testResource(final String name) {
        final URL url = getClass().getResource(name);
        assertNotNull(url, "Test resource not found: " + name);
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid test resource URI: " + url, e);
        }
    }
}
