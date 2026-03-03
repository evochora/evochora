package org.evochora.compiler.module;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for multi-root compilation with source root resolution.
 */
public class SourceRootIntegrationTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        Instruction.init();
    }

    @Test
    @Tag("integration")
    void multiRoot_prefixedImport() throws Exception {
        // Set up: predator source root with a module
        Path predDir = tempDir.resolve("predator");
        Files.createDirectories(predDir);
        Files.writeString(predDir.resolve("lib.evo"), "NOP\n");

        // Main file imports from PRED prefix
        Path mainDir = tempDir.resolve("main");
        Files.createDirectories(mainDir);
        Path mainFile = mainDir.resolve("main.evo");
        Files.writeString(mainFile, ".IMPORT \"PRED:lib.evo\" AS LIB\nNOP\n");

        CompilerOptions options = new CompilerOptions(List.of(
                new SourceRoot(mainDir.toString(), null),
                new SourceRoot(predDir.toString(), "PRED")));

        Compiler compiler = new Compiler();
        String source = Files.readString(mainFile);
        ProgramArtifact artifact = compiler.compile(
                List.of(source.split("\n")), mainFile.toString(), ENV, options);

        assertThat(artifact).isNotNull();
        assertThat(artifact.programId()).isNotEmpty();
    }

    @Test
    @Tag("integration")
    void unknownPrefix_reportsCompilationError() throws Exception {
        Path mainFile = tempDir.resolve("main.evo");
        Files.writeString(mainFile, ".IMPORT \"UNKNOWN:lib.evo\" AS LIB\nNOP\n");

        CompilerOptions options = new CompilerOptions(List.of(
                new SourceRoot(tempDir.toString(), null)));

        Compiler compiler = new Compiler();
        String source = Files.readString(mainFile);
        assertThatThrownBy(() -> compiler.compile(
                List.of(source.split("\n")), mainFile.toString(), ENV, options))
                .isInstanceOf(org.evochora.compiler.api.CompilationException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    @Tag("integration")
    void nestedSource_resolvesFromRoot() throws Exception {
        // Set up: lib/state.evo in subdirectory
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("state.evo"), ".DEFINE MY_CONST DATA:42\n");

        // lib/energy.evo sources lib/state.evo (root-relative path)
        Files.writeString(libDir.resolve("energy.evo"),
                ".SOURCE \"lib/state.evo\"\nNOP\n");

        // main.evo imports lib/energy.evo
        Path mainFile = tempDir.resolve("main.evo");
        Files.writeString(mainFile,
                ".IMPORT \"lib/energy.evo\" AS ENERGY\nNOP\n");

        CompilerOptions options = new CompilerOptions(List.of(
                new SourceRoot(tempDir.toString(), null)));

        Compiler compiler = new Compiler();
        String source = Files.readString(mainFile);
        ProgramArtifact artifact = compiler.compile(
                List.of(source.split("\n")), mainFile.toString(), ENV, options);

        assertThat(artifact).isNotNull();
    }

    @Test
    @Tag("integration")
    void defaultOptions_singleFile() throws Exception {
        Path mainFile = tempDir.resolve("main.evo");
        Files.writeString(mainFile, "NOP\n");

        Compiler compiler = new Compiler();
        String source = Files.readString(mainFile);
        ProgramArtifact artifact = compiler.compile(
                List.of(source.split("\n")), mainFile.toString(), ENV, null);

        assertThat(artifact).isNotNull();
    }
}
