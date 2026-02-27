package org.evochora.compiler.module;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.DependencyScanner;
import org.evochora.compiler.frontend.module.ModuleDescriptor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the Phase 0 dependency scanner that builds the module graph
 * from source directives before compilation.
 */
public class DependencyScannerTest {

    @TempDir
    Path tempDir;

    @Test
    @Tag("unit")
    void singleFileProducesSingleModuleGraph() {
        String source = "NOP\nSETI %DR0 42\n";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics);

        DependencyGraph graph = scanner.scan(source, "/test/main.evo", Path.of("/test"));

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.topologicalOrder()).hasSize(1);
        assertThat(graph.topologicalOrder().get(0).sourcePath()).isEqualTo("/test/main.evo");
    }

    @Test
    @Tag("integration")
    void importProducesTwoModulesInTopologicalOrder() throws Exception {
        Path libFile = tempDir.resolve("lib.evo");
        Files.writeString(libFile, "NOP\n");

        String mainSource = ".IMPORT \"lib.evo\" AS LIB\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics);
        DependencyGraph graph = scanner.scan(mainSource, mainPath, tempDir);

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.topologicalOrder()).hasSize(2);

        // Dependencies come before dependents in topological order
        ModuleDescriptor first = graph.topologicalOrder().get(0);
        ModuleDescriptor second = graph.topologicalOrder().get(1);
        assertThat(first.sourcePath()).contains("lib.evo");
        assertThat(second.sourcePath()).contains("main.evo");
    }

    @Test
    @Tag("integration")
    void circularDependencyReportsError() throws Exception {
        Path aFile = tempDir.resolve("a.evo");
        Path bFile = tempDir.resolve("b.evo");
        Files.writeString(aFile, ".IMPORT \"b.evo\" AS B\n");
        Files.writeString(bFile, ".IMPORT \"a.evo\" AS A\n");

        String aSource = Files.readString(aFile);
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics);
        scanner.scan(aSource, aFile.toString(), tempDir);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).containsIgnoringCase("circular");
    }

    @Test
    @Tag("integration")
    void sourceFileContainingImportReportsError() throws Exception {
        Path incFile = tempDir.resolve("inc.evo");
        Files.writeString(incFile, ".IMPORT \"other.evo\" AS OTHER\n");

        String mainSource = ".SOURCE \"inc.evo\"\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics);
        scanner.scan(mainSource, mainPath, tempDir);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains(".IMPORT");
    }

    @Test
    @Tag("integration")
    void usingClausesAreParsed() throws Exception {
        Path libFile = tempDir.resolve("lib.evo");
        Files.writeString(libFile, ".REQUIRE \"dep.evo\" AS DEP\n");

        Path depFile = tempDir.resolve("dep.evo");
        Files.writeString(depFile, "NOP\n");

        String mainSource = ".IMPORT \"dep.evo\" AS D\n.IMPORT \"lib.evo\" AS LIB USING D AS DEP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics);
        DependencyGraph graph = scanner.scan(mainSource, mainPath, tempDir);

        assertThat(diagnostics.hasErrors()).isFalse();

        // Find the main module (last in topological order)
        ModuleDescriptor mainModule = graph.topologicalOrder().getLast();
        assertThat(mainModule.imports()).hasSize(2);

        ModuleDescriptor.ImportDecl libImport = mainModule.imports().stream()
                .filter(imp -> imp.alias().equalsIgnoreCase("LIB"))
                .findFirst().orElseThrow();
        assertThat(libImport.usings()).hasSize(1);
        assertThat(libImport.usings().get(0).sourceAlias()).isEqualToIgnoringCase("D");
        assertThat(libImport.usings().get(0).targetAlias()).isEqualToIgnoringCase("DEP");
    }

    @Test
    @Tag("integration")
    void requireDeclarationsAreCaptured() throws Exception {
        String source = ".REQUIRE \"dependency.evo\" AS DEP\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics);
        DependencyGraph graph = scanner.scan(source, mainPath, tempDir);

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.topologicalOrder()).hasSize(1);

        ModuleDescriptor module = graph.topologicalOrder().get(0);
        assertThat(module.requires()).hasSize(1);
        assertThat(module.requires().get(0).alias()).isEqualToIgnoringCase("DEP");
        assertThat(module.requires().get(0).path()).isEqualTo("dependency.evo");
    }
}
