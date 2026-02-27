package org.evochora.compiler.module;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for multi-module compilation through the full compiler pipeline.
 * Verifies that .IMPORT produces merged IR with qualified labels and that the
 * resulting artifact is valid.
 */
public class MultiModuleCompilationTest {

    @TempDir
    Path tempDir;

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    @Tag("integration")
    void singleFileCompilationProducesValidArtifact() throws Exception {
        Path mainFile = tempDir.resolve("main.evo");
        Files.writeString(mainFile, "NOP\n");

        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        ProgramArtifact artifact = compiler.compile(
                Files.readAllLines(mainFile),
                mainFile.toAbsolutePath().toString(),
                envProps);

        assertThat(artifact).isNotNull();
        assertThat(artifact.machineCodeLayout()).isNotEmpty();
    }

    @Test
    @Tag("integration")
    void importedModuleCodeIsIncludedInOutput() throws Exception {
        // Library module defines a labeled instruction
        Path libFile = tempDir.resolve("lib.evo");
        Files.writeString(libFile, String.join("\n",
                "HARVEST: NOP",
                ""));

        // Main module imports the library
        Path mainFile = tempDir.resolve("main.evo");
        Files.writeString(mainFile, String.join("\n",
                ".IMPORT \"lib.evo\" AS E",
                "NOP",
                ""));

        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        ProgramArtifact artifact = compiler.compile(
                Files.readAllLines(mainFile),
                mainFile.toAbsolutePath().toString(),
                envProps);

        assertThat(artifact).isNotNull();
        // The imported module's code should be included in the output (at least 2 instructions)
        assertThat(artifact.machineCodeLayout()).hasSizeGreaterThan(1);
    }

    @Test
    @Tag("integration")
    void transitiveDependenciesAreIncluded() throws Exception {
        // Inner library
        Path innerLib = tempDir.resolve("inner.evo");
        Files.writeString(innerLib, "NOP\n");

        // Outer library imports inner
        Path outerLib = tempDir.resolve("outer.evo");
        Files.writeString(outerLib, String.join("\n",
                ".IMPORT \"inner.evo\" AS INNER",
                "NOP",
                ""));

        // Main imports outer (which transitively includes inner)
        Path mainFile = tempDir.resolve("main.evo");
        Files.writeString(mainFile, String.join("\n",
                ".IMPORT \"outer.evo\" AS OUTER",
                "NOP",
                ""));

        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        ProgramArtifact artifact = compiler.compile(
                Files.readAllLines(mainFile),
                mainFile.toAbsolutePath().toString(),
                envProps);

        assertThat(artifact).isNotNull();
        // Should contain code from all three modules (at least 3 instructions)
        assertThat(artifact.machineCodeLayout()).hasSizeGreaterThan(2);
    }

    @Test
    @Tag("integration")
    void orgBeforeImportControlsPlacement() throws Exception {
        Path libFile = tempDir.resolve("lib.evo");
        Files.writeString(libFile, "NOP\n");

        Path mainFile = tempDir.resolve("main.evo");
        Files.writeString(mainFile, String.join("\n",
                ".ORG 0|5",
                ".IMPORT \"lib.evo\" AS LIB",
                ""));

        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        ProgramArtifact artifact = compiler.compile(
                Files.readAllLines(mainFile),
                mainFile.toAbsolutePath().toString(),
                envProps);

        assertThat(artifact).isNotNull();
        assertThat(hasCodeAt(artifact.machineCodeLayout(), 0, 5))
                .as("Imported NOP should be placed at (0,5) as specified by .ORG before .IMPORT")
                .isTrue();
    }

    @Test
    @Tag("integration")
    void orgInsideImportedModuleIsRelativeToImportOrigin() throws Exception {
        Path libFile = tempDir.resolve("lib.evo");
        Files.writeString(libFile, String.join("\n",
                ".ORG 0|3",
                "NOP",
                ""));

        Path mainFile = tempDir.resolve("main.evo");
        Files.writeString(mainFile, String.join("\n",
                ".ORG 0|5",
                ".IMPORT \"lib.evo\" AS LIB",
                ""));

        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        ProgramArtifact artifact = compiler.compile(
                Files.readAllLines(mainFile),
                mainFile.toAbsolutePath().toString(),
                envProps);

        assertThat(artifact).isNotNull();
        assertThat(hasCodeAt(artifact.machineCodeLayout(), 0, 8))
                .as("NOP at .ORG 0|3 inside module imported at .ORG 0|5 should be at (0, 5+3=8)")
                .isTrue();
    }

    @Test
    @Tag("integration")
    void importedModulePlacementDoesNotOverlapMainModule() throws Exception {
        Path libFile = tempDir.resolve("lib.evo");
        Files.writeString(libFile, String.join("\n",
                "NOP",
                "NOP",
                ""));

        Path mainFile = tempDir.resolve("main.evo");
        Files.writeString(mainFile, String.join("\n",
                ".IMPORT \"lib.evo\" AS LIB",
                "NOP",
                "NOP",
                ""));

        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        ProgramArtifact artifact = compiler.compile(
                Files.readAllLines(mainFile),
                mainFile.toAbsolutePath().toString(),
                envProps);

        assertThat(artifact).isNotNull();
        // All coordinates should be unique (no overlap)
        Map<String, Integer> layout = artifact.machineCodeLayout().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> Arrays.toString(e.getKey()),
                        Map.Entry::getValue));
        assertThat(layout.keySet()).hasSize(artifact.machineCodeLayout().size());
    }

    private boolean hasCodeAt(Map<int[], Integer> layout, int... expected) {
        return layout.keySet().stream()
                .anyMatch(coord -> Arrays.equals(coord, expected));
    }
}
