package org.evochora.compiler.frontend;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.DependencyGraph;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.ModulePlacement;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the Lexer phase, which finds the files of a compilation and tokenizes each once: the
 * module placements it builds from the directives that name files, the tokens and lines it keeps
 * of every file, and the context it offers the scan handlers.
 */
public class LexerTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    private SourceRootResolver defaultResolver(Path root) {
        return new SourceRootResolver(List.of(new SourceRoot(".", null)), root);
    }

    private static FeatureRegistry standardRegistry() {
        FeatureRegistry featureRegistry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));
        return featureRegistry;
    }

    /** The scan handlers of the standard features, in a map a test can add its own handlers to. */
    private static Map<String, IDependencyScanHandler> defaultHandlers() {
        return new LinkedHashMap<>(standardRegistry().dependencyScanHandlers());
    }

    private static Lexer lexer(DiagnosticsEngine diagnostics, SourceRootResolver resolver,
                               Map<String, IDependencyScanHandler> handlers, CompilerOptions options) {
        FeatureRegistry features = standardRegistry();
        return new Lexer(diagnostics, resolver, handlers, options, new RuntimeInstructionSetAdapter(),
                features.lexerSymbols(), features.operations(),
                Flags.of(options.defines(), features.operations().reservedNames()));
    }

    @Test
    @Tag("unit")
    void singleFileProducesSingleModuleGraph() {
        String source = "NOP\nSETI %DR0 42\n";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SourceRootResolver resolver = defaultResolver(Path.of("/test"));
        Lexer lexer = lexer(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());

        DependencyGraph graph = lexer.read(source, "/test/main.evo", "");

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
        Lexer lexer = lexer(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = lexer.read(mainSource, mainPath, "");

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
    void fileImportedTwice_isPlacedAtEveryImport_andItsTokensKeptOnce() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"), "NOP\n");
        String mainSource = ".IMPORT \"lib.evo\" AS FIRST\n.IMPORT \"lib.evo\" AS SECOND\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = lexer(diagnostics, defaultResolver(tempDir), defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = lexer.read(mainSource, mainPath, "MAIN");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.placements()).extracting(ModulePlacement::aliasChain)
                .containsExactly("MAIN.FIRST", "MAIN.SECOND", "MAIN");
        assertThat(graph.tokens()).containsOnlyKeys(mainPath, resolved("lib.evo"));
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
        Lexer lexer = lexer(diagnostics, defaultResolver(tempDir), defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = lexer.read(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.placements()).extracting(ModulePlacement::aliasChain)
                .containsExactly("A.M", "A", "B.M", "B", "");
        assertThat(graph.placements().getLast().dependencies())
                .extracting(d -> ((org.evochora.compiler.features.importdir.ImportDependencyInfo) d).aliasChain())
                .containsExactly("A", "B");
    }

    @Test
    @Tag("integration")
    void lines_holdEveryFileReadOnce_theMainFileIncluded() throws Exception {
        Files.writeString(tempDir.resolve("m.evo"), ".SOURCE \"inc.evo\"\nNOP\n");
        Files.writeString(tempDir.resolve("inc.evo"), "NOP\n");
        String mainSource = ".IMPORT \"m.evo\" AS A\n.SOURCE \"inc.evo\"\n.IMPORT \"m.evo\" AS B\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = lexer(diagnostics, defaultResolver(tempDir), defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = lexer.read(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isFalse();
        String module = resolved("m.evo");
        String inc = resolved("inc.evo");
        assertThat(graph.lines()).containsOnlyKeys(mainPath, module, inc);
        assertThat(graph.lines().get(mainPath))
                .containsExactly(".IMPORT \"m.evo\" AS A", ".SOURCE \"inc.evo\"", ".IMPORT \"m.evo\" AS B");
        assertThat(graph.lines().get(module)).containsExactly(".SOURCE \"inc.evo\"", "NOP");
        assertThat(graph.lines().get(inc)).containsExactly("NOP");
    }

    @Test
    @Tag("integration")
    void circularDependencyReportsError() throws Exception {
        Path aFile = tempDir.resolve("a.evo");
        Path bFile = tempDir.resolve("b.evo");
        Files.writeString(aFile, ".IMPORT \"b.evo\" AS B\n");
        Files.writeString(bFile, "# A comment line.\n.IMPORT \"a.evo\" AS A\n");

        String aSource = Files.readString(aFile);
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SourceRootResolver resolver = defaultResolver(tempDir);
        Lexer lexer = lexer(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        lexer.read(aSource, aFile.toString().replace('\\', '/'), "");

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("b.evo:2: Circular dependency detected: " + aFile.toString().replace('\\', '/'));
    }

    @Test
    @Tag("integration")
    void sourceFileContainingImportReportsError() throws Exception {
        Path incFile = tempDir.resolve("inc.evo");
        Files.writeString(incFile, "# A comment line.\n.IMPORT \"other.evo\" AS OTHER\n");

        String mainSource = ".SOURCE \"inc.evo\"\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SourceRootResolver resolver = defaultResolver(tempDir);
        Lexer lexer = lexer(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        lexer.read(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary())
                .contains("inc.evo:2: .IMPORT not allowed in a .SOURCE file")
                .contains("inc.evo:2: Module file not found: other.evo");
    }

    @Test
    @Tag("integration")
    void usingClausesAreParsed() throws Exception {
        Path libFile = tempDir.resolve("lib.evo");
        Files.writeString(libFile, ".REQUIRE MODULE \"dep.evo\" AS DEP\n");

        Path depFile = tempDir.resolve("dep.evo");
        Files.writeString(depFile, "NOP\n");

        String mainSource = ".IMPORT \"dep.evo\" AS D\n.IMPORT \"lib.evo\" AS LIB USING MODULE D AS DEP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SourceRootResolver resolver = defaultResolver(tempDir);
        Lexer lexer = lexer(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = lexer.read(mainSource, mainPath, "");

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
        assertThat(libImport.path()).isEqualTo("lib.evo");
        assertThat(libImport.alias()).isEqualToIgnoringCase("LIB");
    }

    @Test
    @Tag("integration")
    void requireDeclarationsAreCaptured() throws Exception {
        String source = ".REQUIRE MODULE \"dependency.evo\" AS DEP\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SourceRootResolver resolver = defaultResolver(tempDir);
        Lexer lexer = lexer(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        DependencyGraph graph = lexer.read(source, mainPath, "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(graph.placements()).hasSize(1);

        ModulePlacement module = graph.placements().get(0);
        List<org.evochora.compiler.features.importdir.RequireDependencyInfo> requires = module.dependencies().stream()
                .filter(d -> d instanceof org.evochora.compiler.features.importdir.RequireDependencyInfo)
                .map(d -> (org.evochora.compiler.features.importdir.RequireDependencyInfo) d)
                .toList();
        assertThat(requires).hasSize(1);
    }

    @Test
    @Tag("integration")
    void threeModuleCycle_isDetected() throws Exception {
        Files.writeString(tempDir.resolve("a.evo"), ".IMPORT \"b.evo\" AS B\nNOP\n");
        Files.writeString(tempDir.resolve("b.evo"), ".IMPORT \"c.evo\" AS C\nNOP\n");
        Files.writeString(tempDir.resolve("c.evo"), "# A comment line.\n.IMPORT \"a.evo\" AS A\nNOP\n");

        String mainSource = ".IMPORT \"a.evo\" AS A\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SourceRootResolver resolver = defaultResolver(tempDir);
        Lexer lexer = lexer(diagnostics, resolver, defaultHandlers(), CompilerOptions.defaults());
        lexer.read(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary())
                .contains("c.evo:2: Circular dependency detected: " + tempDir.resolve("a.evo").toString().replace('\\', '/'));
    }

    /** A scan handler that hands the line of its directive word and the context to an action. */
    private static IDependencyScanHandler handler(BiConsumer<DirectiveLine, IDependencyScanContext> action) {
        return action::accept;
    }

    /** The texts of a line's tokens, joined by single spaces; {@code null} for no line. */
    private static String text(List<Token> line) {
        return line == null ? null : line.stream().map(Token::text).collect(Collectors.joining(" "));
    }

    /** Feature state kept in the scan slot by the tests below. */
    private static final class SeenFiles {
        final List<String> paths = new ArrayList<>();
    }

    @Test
    @Tag("integration")
    void stateSlot_spansTheFilesOfOneScan_andIsCreatedOnce() throws Exception {
        Files.writeString(tempDir.resolve("inc.evo"), ".MARK\n");
        String mainSource = ".MARK\n.SOURCE \"inc.evo\"\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        AtomicInteger created = new AtomicInteger();
        List<SeenFiles> instances = new ArrayList<>();
        Map<String, IDependencyScanHandler> handlers = defaultHandlers();
        handlers.put(".MARK", handler((line, ctx) -> {
            SeenFiles seen = ctx.getOrCreate(SeenFiles.class, () -> {
                created.incrementAndGet();
                return new SeenFiles();
            });
            seen.paths.add(ctx.sourcePath());
            instances.add(seen);
        }));

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = lexer(diagnostics, defaultResolver(tempDir), handlers, CompilerOptions.defaults());
        lexer.read(mainSource, mainPath, "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(created).hasValue(1);
        assertThat(instances).hasSize(2);
        assertThat(instances.get(1)).isSameAs(instances.get(0));
        assertThat(instances.get(0).paths).containsExactly(mainPath, resolved("inc.evo"));

        lexer.read(mainSource, mainPath, "");
        assertThat(created).as("a second scan starts with an empty slot").hasValue(2);
    }

    @Test
    @Tag("unit")
    void stateSlot_keepsDifferentKeysApart() {
        List<Object> results = new ArrayList<>();
        Map<String, IDependencyScanHandler> handlers = Map.of(".MARK", handler((line, ctx) -> {
            results.add(ctx.getOrCreate(SeenFiles.class, SeenFiles::new));
            results.add(ctx.getOrCreate(AtomicInteger.class, AtomicInteger::new));
        }));

        Lexer lexer = lexer(new DiagnosticsEngine(), defaultResolver(Path.of("/test")), handlers, CompilerOptions.defaults());
        lexer.read(".MARK\n.MARK\n", "/test/main.evo", "");

        assertThat(results).hasSize(4);
        assertThat(results.get(0)).isInstanceOf(SeenFiles.class).isSameAs(results.get(2));
        assertThat(results.get(1)).isInstanceOf(AtomicInteger.class).isSameAs(results.get(3));
    }

    @Test
    @Tag("unit")
    void nextLine_returnsTheTokensOfTheFollowingStatementLines_andIsNotDispatchedAgain() {
        List<String> taken = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        List<String> marked = new ArrayList<>();
        Map<String, IDependencyScanHandler> handlers = new LinkedHashMap<>();
        handlers.put(".TAKE2", handler((line, ctx) -> {
            lineNumbers.add(ctx.lineNumber());
            taken.add(text(ctx.nextLine()));
            taken.add(text(ctx.nextLine()));
            lineNumbers.add(ctx.lineNumber());
        }));
        handlers.put(".MARK", handler((line, ctx) ->
                marked.add(line.operands().get(0).text() + "@" + ctx.lineNumber())));

        String source = String.join("\n",
                ".TAKE2",
                "   .MARK A   # a comment",
                "# a line with nothing but a comment",
                "",
                ".MARK B",
                ".MARK C");
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = lexer(diagnostics, defaultResolver(Path.of("/test")), handlers, CompilerOptions.defaults());
        lexer.read(source, "/test/main.evo", "");

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(taken).containsExactly(".MARK A", ".MARK B");
        assertThat(lineNumbers).containsExactly(1, 5);
        assertThat(marked).containsExactly("C@6");
    }

    @Test
    @Tag("unit")
    void nextLine_returnsNullAtTheEndOfTheFile() {
        List<String> taken = new ArrayList<>();
        Map<String, IDependencyScanHandler> handlers = Map.of(".TAKEALL", handler((line, ctx) -> {
            for (int i = 0; i < 4; i++) {
                taken.add(text(ctx.nextLine()));
            }
        }));

        Lexer lexer = lexer(new DiagnosticsEngine(), defaultResolver(Path.of("/test")), handlers, CompilerOptions.defaults());
        lexer.read(".TAKEALL\nX\n\nY\n", "/test/main.evo", "");

        assertThat(taken).containsExactly("X", "Y", null, null);
    }

    @Test
    @Tag("unit")
    void options_areThoseTheLexerWasCreatedWith() {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(".", null)), Map.of("FLAG", OptionalLong.empty()), Map.of());
        List<CompilerOptions> seen = new ArrayList<>();
        Map<String, IDependencyScanHandler> handlers = Map.of(".MARK", handler((line, ctx) -> seen.add(ctx.options())));

        Lexer lexer = lexer(new DiagnosticsEngine(), defaultResolver(Path.of("/test")), handlers, options);
        lexer.read(".MARK\n", "/test/main.evo", "");

        assertThat(seen).containsExactly(options);
    }

    /**
     * Returns the path of a file in the source root as the compiler writes a resolved path, with
     * forward slashes on every platform.
     */
    private String resolved(String fileName) {
        return tempDir.resolve(fileName).toString().replace('\\', '/');
    }
}
