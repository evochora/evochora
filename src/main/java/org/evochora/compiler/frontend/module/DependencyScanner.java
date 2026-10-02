package org.evochora.compiler.frontend.module;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.util.SourceLoader;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;

/**
 * Phase 0: Scans source files for dependency directives and builds a {@link DependencyGraph}.
 * Dispatches to registered {@link IDependencyScanHandler} implementations for all directive
 * detection and processing. The scanner itself has zero knowledge of specific directives.
 *
 * <p>This phase scans raw source text via regex rather than running the lexer. This means
 * directive syntax is maintained in two places: regex patterns in the scan handlers and
 * token-based parsing in the parser handlers. This is a deliberate trade-off to avoid a
 * full lex pass solely for dependency discovery.</p>
 */
public final class DependencyScanner {

    private final DiagnosticsEngine diagnostics;
    private final SourceRootResolver resolver;
    private final List<IDependencyScanHandler> handlers;
    private final CompilerOptions options;

    /**
     * Creates a scanner whose entire directive knowledge comes from the supplied handlers.
     * Each non-empty source line is offered to the handlers in list order and the first
     * one whose pattern matches consumes it, so the order of the list decides precedence;
     * a line a handler takes through {@link IDependencyScanContext#nextLine()} is not offered.
     * The scanner keeps nothing between calls; everything a scan finds is in the graph it returns.
     *
     * @param diagnostics Collects errors for unresolvable paths, circular imports and
     *                    directives used where they are not allowed.
     * @param resolver    Resolves directive paths, including the {@code PREFIX:path} form,
     *                    relative to the file the directive appears in.
     * @param handlers    Scan handlers, kept by reference and tried in list order.
     * @param options     The options of the compilation, offered to the handlers through
     *                    {@link IDependencyScanContext#options()}; must not be null.
     */
    public DependencyScanner(DiagnosticsEngine diagnostics, SourceRootResolver resolver,
                             List<IDependencyScanHandler> handlers, CompilerOptions options) {
        this.diagnostics = diagnostics;
        this.resolver = resolver;
        this.handlers = handlers;
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Scans the main file and all its transitive dependencies, building a dependency graph. The
     * scan descends from the main module along the imports in the order they stand, as the
     * preprocessor inlines them: a module file is scanned at every import, under the alias chain
     * of that import, with the state of every feature as the scan reaches the import.
     *
     * @param mainContent    The full source text of the main file; it is used as given and
     *                       never re-read from disk.
     * @param mainWrittenPath The name the main file was given to the compiler under, which
     *                       names it where the program's text is shown.
     * @param mainPath       Path identifying the main module, also used as the file location
     *                       of errors reported while scanning it.
     * @param rootAliasChain The alias chain of the main module, the one the preprocessor starts
     *                       with; empty for a main module compiled without a prefix.
     * @return A graph containing a placement of the main module and of every module at every
     *         import reached, each after the placements it imports, the text of every file
     *         found on the way, and that text once per placement it stands in. An empty graph is
     *         returned if scanning reported any error.
     */
    public DependencyGraph scan(String mainContent, String mainWrittenPath, String mainPath, String rootAliasChain) {
        ScanState state = new ScanState();
        scanModule(state, rootAliasChain, mainPath, mainWrittenPath, mainContent);

        if (diagnostics.hasErrors()) {
            return new DependencyGraph(List.of(), List.of(), Map.of(), Map.of(), mainPath);
        }

        Map<String, String> moduleContents = new LinkedHashMap<>();
        for (ModulePlacement placement : state.placements) {
            if (!placement.sourcePath().equals(mainPath)) {
                moduleContents.putIfAbsent(placement.sourcePath(), state.loaded.get(placement.sourcePath()));
            }
        }
        return new DependencyGraph(List.copyOf(state.placements), List.copyOf(state.sourceFiles.values()),
                Collections.unmodifiableMap(moduleContents),
                Collections.unmodifiableMap(state.sourceContents), mainPath);
    }

    /**
     * Everything one scan discovers: the placements in the order their scans end, the files on
     * the current import path (for cycle detection), the content of every file read, the text
     * of the source files, and every file's text per placement in the order the scans begin,
     * with its lines split once per file; and the state features keep through
     * {@link IDependencyScanContext#getOrCreate}, which spans every file of the scan.
     */
    private static final class ScanState {
        final List<ModulePlacement> placements = new ArrayList<>();
        final Map<PlacedPath, SourceFile> sourceFiles = new LinkedHashMap<>();
        final Map<String, List<String>> linesByPath = new HashMap<>();
        final Set<String> importPath = new HashSet<>();
        final Map<String, String> loaded = new HashMap<>();
        final Map<String, String> sourceContents = new LinkedHashMap<>();
        final Map<Class<?>, Object> featureState = new HashMap<>();
    }

    /**
     * Scans one placement of a module and records it once its own imports are scanned, so that
     * a placement comes after the placements it imports.
     */
    private void scanModule(ScanState state, String aliasChain, String sourcePath, String writtenPath, String content) {
        if (!state.importPath.add(sourcePath)) {
            diagnostics.reportError("Circular dependency detected: " + sourcePath, sourcePath, 0);
            return;
        }
        state.loaded.putIfAbsent(sourcePath, content);
        recordSourceFile(state, aliasChain, writtenPath, sourcePath, content);

        List<IDependencyInfo> dependencies = scanLines(state, aliasChain, sourcePath, content, false);
        state.placements.add(new ModulePlacement(aliasChain, sourcePath, dependencies));
        state.importPath.remove(sourcePath);
    }

    /**
     * Scans a .SOURCE file for nested directives. Every dependency found is asked whether it may
     * appear in a source file, and one that says no is reported as an error. Today .IMPORT and
     * .REQUIRE say no while .SOURCE inherits the permissive default.
     */
    private void scanSourceFile(ScanState state, String aliasChain, String sourcePath, String writtenPath, String content) {
        recordSourceFile(state, aliasChain, writtenPath, sourcePath, content);
        scanLines(state, aliasChain, sourcePath, content, true);
    }

    /** A file in a placement, the key under which its text is recorded once. */
    private record PlacedPath(String placement, String path) {
    }

    /**
     * Records a file's text under the placement it stands in, the first time the file is met in
     * that placement; a file included twice into one placement keeps the path written first.
     */
    private static void recordSourceFile(ScanState state, String placement, String writtenPath, String sourcePath,
                                         String content) {
        state.sourceFiles.computeIfAbsent(new PlacedPath(placement, sourcePath), key -> new SourceFile(placement, writtenPath,
                sourcePath, state.linesByPath.computeIfAbsent(sourcePath, path -> List.of(content.split("\\r?\\n")))));
    }

    /**
     * Core line-by-line scanning with generic handler dispatch. The lines are read through the
     * context's cursor, so that a line a handler takes with {@link IDependencyScanContext#nextLine()}
     * is skipped here.
     * @param sourceFileMode If true, every dependency a handler adds is checked against
     *                        {@link IDependencyInfo#allowedInSourceFile()} and reported as an error
     *                        at its line when it is not allowed there.
     */
    private List<IDependencyInfo> scanLines(ScanState state, String aliasChain, String sourcePath, String content,
                                            boolean sourceFileMode) {
        ScanContext ctx = new ScanContext(state, aliasChain, sourcePath, sourceFileMode, content.split("\\r?\\n"));

        String line;
        while ((line = ctx.nextLine()) != null) {
            for (IDependencyScanHandler handler : handlers) {
                Matcher matcher = handler.pattern().matcher(line);
                if (matcher.matches()) {
                    handler.handleMatch(matcher, ctx);
                    break;
                }
            }
        }

        return ctx.collectedDependencies();
    }

    String loadContent(String resolvedPath) throws IOException {
        if (SourceLoader.isHttpUrl(resolvedPath)) {
            return SourceLoader.loadHttp(resolvedPath).content();
        }
        Path filePath = Path.of(resolvedPath);
        if (Files.exists(filePath)) {
            return SourceLoader.loadFile(filePath).content();
        }
        throw new IOException("File not found: " + resolvedPath);
    }

    /**
     * Inner context implementation passed to handlers during scanning.
     */
    private class ScanContext implements IDependencyScanContext {
        private final ScanState state;
        private final String aliasChain;
        private final String sourcePath;
        private final boolean sourceFileMode;
        private final String[] lines;
        private int nextIndex;
        private int lineNumber;
        private final List<IDependencyInfo> collected = new ArrayList<>();

        ScanContext(ScanState state, String aliasChain, String sourcePath, boolean sourceFileMode, String[] lines) {
            this.state = state;
            this.aliasChain = aliasChain;
            this.sourcePath = sourcePath;
            this.sourceFileMode = sourceFileMode;
            this.lines = lines;
        }

        List<IDependencyInfo> collectedDependencies() {
            return collected;
        }

        @Override
        public String resolve(String path) throws SourceRootResolver.UnknownPrefixException {
            return resolver.resolve(path, sourcePath);
        }

        @Override
        public String loadContent(String resolvedPath) throws IOException {
            String content = state.loaded.get(resolvedPath);
            if (content == null) {
                content = DependencyScanner.this.loadContent(resolvedPath);
                state.loaded.put(resolvedPath, content);
            }
            return content;
        }

        @Override
        public void registerSourceContent(String resolvedPath, String content) {
            state.sourceContents.put(resolvedPath, content);
        }

        @Override
        public void reportError(String message) {
            diagnostics.reportError(message, sourcePath, lineNumber);
        }

        @Override
        public void scanNestedModule(String resolvedPath, String writtenPath, String content, String placementChain) {
            DependencyScanner.this.scanModule(state, placementChain, resolvedPath, writtenPath, content);
        }

        @Override
        public void scanNestedSourceFile(String resolvedPath, String writtenPath, String content) {
            DependencyScanner.this.scanSourceFile(state, aliasChain, resolvedPath, writtenPath, content);
        }

        @Override
        public String placementChain() {
            return aliasChain;
        }

        @Override
        public void addDependency(IDependencyInfo info) {
            if (sourceFileMode && !info.allowedInSourceFile()) {
                reportError(info.directiveName() + " not allowed in a .SOURCE file");
                return;
            }
            collected.add(info);
        }

        @Override
        public String sourcePath() {
            return sourcePath;
        }

        @Override
        public int lineNumber() {
            return lineNumber;
        }

        @Override
        public String nextLine() {
            while (nextIndex < lines.length) {
                String line = lines[nextIndex++].trim();
                int commentIdx = line.indexOf('#');
                if (commentIdx >= 0) {
                    line = line.substring(0, commentIdx).trim();
                }
                if (!line.isEmpty()) {
                    lineNumber = nextIndex;
                    return line;
                }
            }
            return null;
        }

        @Override
        public CompilerOptions options() {
            return options;
        }

        @Override
        public <T> T getOrCreate(Class<T> key, Supplier<T> factory) {
            return key.cast(state.featureState.computeIfAbsent(key, k -> factory.get()));
        }
    }
}
