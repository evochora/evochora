package org.evochora.compiler.module;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.DependencyScanner;
import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;
import org.evochora.compiler.frontend.module.ModulePlacement;
import org.evochora.compiler.util.SourceRootResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the Phase 0 dependency scanner that builds the module graph
 * from source directives before compilation.
 */
public class DependencyScannerTest {

    @TempDir
    Path tempDir;

    private SourceRootResolver defaultResolver(Path root) {
        return new SourceRootResolver(List.of(new SourceRoot(".", null)), root);
    }

    private List<IDependencyScanHandler> defaultHandlers() {
        FeatureRegistry featureRegistry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));
        return featureRegistry.dependencyScanHandlers();
    }

    @Test
    @Tag("unit")
    void singleFileProducesSingleModuleGraph() {
        String source = "NOP\nSETI %DR0 42\n";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SourceRootResolver resolver = defaultResolver(Path.of("/test"));
        DependencyScanner scanner = new DependencyScanner(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());

        DependencyGraph graph = scanner.scan(source, "/test/main.evo", "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.placements()).hasSize(1);
        assertThat(graph.placements().get(0).sourcePath()).isEqualTo("/test/main.evo");
    }

    @Test
    @Tag("integration")
    void importProducesTwoPlacements_theImportedOneFirst() throws Exception {
        Path libFile = tempDir.resolve("lib.evo");
        Files.writeString(libFile, "NOP\n");

        String mainSource = ".IMPORT \"lib.evo\" AS LIB\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SourceRootResolver resolver = defaultResolver(tempDir);
        DependencyScanner scanner = new DependencyScanner(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = scanner.scan(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.placements()).hasSize(2);

        // A placement comes after the placements it imports
        ModulePlacement first = graph.placements().get(0);
        ModulePlacement second = graph.placements().get(1);
        assertThat(first.sourcePath()).contains("lib.evo");
        assertThat(first.aliasChain()).isEqualTo("LIB");
        assertThat(second.sourcePath()).contains("main.evo");
        assertThat(second.aliasChain()).isEmpty();
    }

    @Test
    @Tag("integration")
    void fileImportedTwice_isPlacedAtEveryImport_andItsContentKeptOnce() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"), "NOP\n");
        String mainSource = ".IMPORT \"lib.evo\" AS FIRST\n.IMPORT \"lib.evo\" AS SECOND\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics, defaultResolver(tempDir), defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = scanner.scan(mainSource, mainPath, "MAIN");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.placements()).extracting(ModulePlacement::aliasChain)
                .containsExactly("MAIN.FIRST", "MAIN.SECOND", "MAIN");
        assertThat(graph.moduleContents()).containsOnlyKeys(tempDir.resolve("lib.evo").toString());
    }

    @Test
    @Tag("integration")
    void diamond_placesTheSharedModuleUnderEachImporter_eachBeforeItsImporter() throws Exception {
        Files.writeString(tempDir.resolve("m.evo"), "NOP\n");
        Files.writeString(tempDir.resolve("a.evo"), ".IMPORT \"m.evo\" AS M\n");
        Files.writeString(tempDir.resolve("b.evo"), ".IMPORT \"m.evo\" AS M\n");
        String mainSource = ".IMPORT \"a.evo\" AS A\n.IMPORT \"b.evo\" AS B\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics, defaultResolver(tempDir), defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = scanner.scan(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.placements()).extracting(ModulePlacement::aliasChain)
                .containsExactly("A.M", "A", "B.M", "B", "");
        assertThat(graph.placements().getLast().dependencies())
                .extracting(d -> ((org.evochora.compiler.features.importdir.ImportDependencyInfo) d).aliasChain())
                .containsExactly("A", "B");
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
        SourceRootResolver resolver = defaultResolver(tempDir);
        DependencyScanner scanner = new DependencyScanner(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        scanner.scan(aSource, aFile.toString(), "");

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
        SourceRootResolver resolver = defaultResolver(tempDir);
        DependencyScanner scanner = new DependencyScanner(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        scanner.scan(mainSource, mainPath, "");

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
        SourceRootResolver resolver = defaultResolver(tempDir);
        DependencyScanner scanner = new DependencyScanner(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = scanner.scan(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isFalse();

        // The main module's placement is the last
        ModulePlacement mainModule = graph.placements().getLast();
        List<org.evochora.compiler.features.importdir.ImportDependencyInfo> imports = mainModule.dependencies().stream()
                .filter(d -> d instanceof org.evochora.compiler.features.importdir.ImportDependencyInfo)
                .map(d -> (org.evochora.compiler.features.importdir.ImportDependencyInfo) d)
                .toList();
        assertThat(imports).hasSize(2);

        org.evochora.compiler.features.importdir.ImportDependencyInfo libImport = imports.stream()
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
        SourceRootResolver resolver = defaultResolver(tempDir);
        DependencyScanner scanner = new DependencyScanner(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = scanner.scan(source, mainPath, "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.placements()).hasSize(1);

        ModulePlacement module = graph.placements().get(0);
        List<org.evochora.compiler.features.require.RequireDependencyInfo> requires = module.dependencies().stream()
                .filter(d -> d instanceof org.evochora.compiler.features.require.RequireDependencyInfo)
                .map(d -> (org.evochora.compiler.features.require.RequireDependencyInfo) d)
                .toList();
        assertThat(requires).hasSize(1);
        assertThat(requires.get(0).alias()).isEqualToIgnoringCase("DEP");
        assertThat(requires.get(0).path()).isEqualTo("dependency.evo");
    }

    @Test
    @Tag("integration")
    void threeModuleCycle_isDetected() throws Exception {
        Files.writeString(tempDir.resolve("a.evo"), ".IMPORT \"b.evo\" AS B\nNOP\n");
        Files.writeString(tempDir.resolve("b.evo"), ".IMPORT \"c.evo\" AS C\nNOP\n");
        Files.writeString(tempDir.resolve("c.evo"), ".IMPORT \"a.evo\" AS A\nNOP\n");

        String mainSource = ".IMPORT \"a.evo\" AS A\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SourceRootResolver resolver = defaultResolver(tempDir);
        DependencyScanner scanner = new DependencyScanner(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        scanner.scan(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().toLowerCase().contains("circular")))
                .as("Expected circular dependency error")
                .isTrue();
    }

    /** A scan handler built from a pattern and an action, for tests of the scan context. */
    private static IDependencyScanHandler handler(String regex, BiConsumer<Matcher, IDependencyScanContext> action) {
        Pattern pattern = Pattern.compile(regex);
        return new IDependencyScanHandler() {
            @Override
            public Pattern pattern() {
                return pattern;
            }

            @Override
            public void handleMatch(Matcher matcher, IDependencyScanContext ctx) {
                action.accept(matcher, ctx);
            }
        };
    }

    /** Feature state kept in the scan slot by the tests below. */
    private static final class SeenFiles {
        final List<String> paths = new ArrayList<>();
    }

    @Test
    @Tag("integration")
    void stateSlot_spansTheFilesOfOneScan_andIsCreatedOnce() throws Exception {
        Files.writeString(tempDir.resolve("inc.evo"), "MARK\n");
        String mainSource = "MARK\n.SOURCE \"inc.evo\"\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        AtomicInteger created = new AtomicInteger();
        List<SeenFiles> instances = new ArrayList<>();
        List<IDependencyScanHandler> handlers = new ArrayList<>(defaultHandlers());
        handlers.add(handler("MARK", (m, ctx) -> {
            SeenFiles seen = ctx.getOrCreate(SeenFiles.class, () -> {
                created.incrementAndGet();
                return new SeenFiles();
            });
            seen.paths.add(ctx.sourcePath());
            instances.add(seen);
        }));

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics, defaultResolver(tempDir), handlers, CompilerOptions.defaults());
        scanner.scan(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(created).hasValue(1);
        assertThat(instances).hasSize(2);
        assertThat(instances.get(1)).isSameAs(instances.get(0));
        assertThat(instances.get(0).paths).containsExactly(mainPath, tempDir.resolve("inc.evo").toString());

        scanner.scan(mainSource, mainPath, "");
        assertThat(created).as("a second scan starts with an empty slot").hasValue(2);
    }

    @Test
    @Tag("unit")
    void stateSlot_keepsDifferentKeysApart() {
        List<Object> results = new ArrayList<>();
        List<IDependencyScanHandler> handlers = List.of(handler("MARK", (m, ctx) -> {
            results.add(ctx.getOrCreate(SeenFiles.class, SeenFiles::new));
            results.add(ctx.getOrCreate(AtomicInteger.class, AtomicInteger::new));
        }));

        DependencyScanner scanner = new DependencyScanner(new DiagnosticsEngine(), defaultResolver(Path.of("/test")), handlers, CompilerOptions.defaults());
        scanner.scan("MARK\nMARK\n", "/test/main.evo", "");

        assertThat(results).hasSize(4);
        assertThat(results.get(0)).isInstanceOf(SeenFiles.class).isSameAs(results.get(2));
        assertThat(results.get(1)).isInstanceOf(AtomicInteger.class).isSameAs(results.get(3));
    }

    @Test
    @Tag("unit")
    void nextLine_returnsFollowingLinesStrippedAndTrimmed_andIsNotDispatchedAgain() {
        List<String> taken = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        List<String> marked = new ArrayList<>();
        List<IDependencyScanHandler> handlers = List.of(
                handler("TAKE2", (m, ctx) -> {
                    lineNumbers.add(ctx.lineNumber());
                    taken.add(ctx.nextLine());
                    taken.add(ctx.nextLine());
                    lineNumbers.add(ctx.lineNumber());
                }),
                handler("MARK (\\w+)", (m, ctx) -> marked.add(m.group(1) + "@" + ctx.lineNumber())));

        String source = String.join("\n",
                "TAKE2",
                "   MARK A   # a comment",
                "# a line with nothing but a comment",
                "",
                "MARK B",
                "MARK C");
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        DependencyScanner scanner = new DependencyScanner(diagnostics, defaultResolver(Path.of("/test")), handlers, CompilerOptions.defaults());
        scanner.scan(source, "/test/main.evo", "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(taken).containsExactly("MARK A", "MARK B");
        assertThat(lineNumbers).containsExactly(1, 5);
        assertThat(marked).containsExactly("C@6");
    }

    @Test
    @Tag("unit")
    void nextLine_returnsNullAtTheEndOfTheFile() {
        List<String> taken = new ArrayList<>();
        List<IDependencyScanHandler> handlers = List.of(handler("TAKEALL", (m, ctx) -> {
            for (int i = 0; i < 4; i++) {
                taken.add(ctx.nextLine());
            }
        }));

        DependencyScanner scanner = new DependencyScanner(new DiagnosticsEngine(), defaultResolver(Path.of("/test")), handlers, CompilerOptions.defaults());
        scanner.scan("TAKEALL\nX\n\nY\n", "/test/main.evo", "");

        assertThat(taken).containsExactly("X", "Y", null, null);
    }

    @Test
    @Tag("unit")
    void options_areThoseTheScannerWasCreatedWith() {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(".", null)), Map.of("FLAG", OptionalInt.empty()));
        List<CompilerOptions> seen = new ArrayList<>();
        List<IDependencyScanHandler> handlers = List.of(handler("MARK", (m, ctx) -> seen.add(ctx.options())));

        DependencyScanner scanner = new DependencyScanner(new DiagnosticsEngine(), defaultResolver(Path.of("/test")), handlers, options);
        scanner.scan("MARK\n", "/test/main.evo", "");

        assertThat(seen).containsExactly(options);
    }
}
