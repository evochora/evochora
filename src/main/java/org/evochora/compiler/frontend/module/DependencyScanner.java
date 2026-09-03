package org.evochora.compiler.frontend.module;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.util.SourceLoader;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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

    /**
     * Creates a scanner whose entire directive knowledge comes from the supplied handlers.
     * Each non-empty source line is offered to the handlers in list order and the first
     * one whose pattern matches consumes it, so the order of the list decides precedence.
     * The scanner keeps nothing between calls; everything a scan finds is in the graph it returns.
     *
     * @param diagnostics Collects errors for unresolvable paths, circular imports and
     *                    directives used where they are not allowed.
     * @param resolver    Resolves directive paths, including the {@code PREFIX:path} form,
     *                    relative to the file the directive appears in.
     * @param handlers    Scan handlers, kept by reference and tried in list order.
     */
    public DependencyScanner(DiagnosticsEngine diagnostics, SourceRootResolver resolver, List<IDependencyScanHandler> handlers) {
        this.diagnostics = diagnostics;
        this.resolver = resolver;
        this.handlers = handlers;
    }

    /**
     * Scans the main file and all its transitive dependencies, building a dependency graph.
     *
     * @param mainContent The full source text of the main file; it is used as given and
     *                    never re-read from disk.
     * @param mainPath    Path identifying the main module, also used as the file location
     *                    of errors reported while scanning it.
     * @return A graph containing the main module, every transitively reachable module ordered
     *         so that dependencies precede their dependents, and the text of every source
     *         file found on the way. An empty graph is returned if scanning reported any error.
     */
    public DependencyGraph scan(String mainContent, String mainPath) {
        ScanState state = new ScanState();
        ModuleId mainId = new ModuleId(mainPath);
        scanModule(state, mainId, mainPath, mainContent);

        if (diagnostics.hasErrors()) {
            return new DependencyGraph(List.of(), Map.of());
        }

        List<ModuleDescriptor> sorted = topologicalSort(state);
        return new DependencyGraph(sorted, Collections.unmodifiableMap(state.sourceContents));
    }

    /**
     * Everything one scan discovers: the modules by identity, the modules whose scan is still
     * open (for cycle detection) and the text of the source files.
     */
    private static final class ScanState {
        final Map<ModuleId, ModuleDescriptor> descriptors = new LinkedHashMap<>();
        final Set<ModuleId> visiting = new LinkedHashSet<>();
        final Map<String, String> sourceContents = new LinkedHashMap<>();
    }

    private void scanModule(ScanState state, ModuleId moduleId, String sourcePath, String content) {
        if (state.descriptors.containsKey(moduleId)) return;

        if (state.visiting.contains(moduleId)) {
            diagnostics.reportError("Circular dependency detected: " + moduleId.path(), sourcePath, 0);
            return;
        }
        state.visiting.add(moduleId);

        List<IDependencyInfo> dependencies = scanLines(state, sourcePath, content, false);
        ModuleDescriptor descriptor = new ModuleDescriptor(moduleId, sourcePath, content, dependencies);
        state.descriptors.put(moduleId, descriptor);
        state.visiting.remove(moduleId);
    }

    /**
     * Scans a .SOURCE file for nested directives. Every dependency found is asked whether it may
     * appear in a source file, and one that says no is reported as an error. Today .IMPORT and
     * .REQUIRE say no while .SOURCE inherits the permissive default.
     */
    private void scanSourceFile(ScanState state, String sourcePath, String content) {
        scanLines(state, sourcePath, content, true);
    }

    /**
     * Core line-by-line scanning with generic handler dispatch.
     * @param sourceFileMode If true, every collected dependency is checked against
     *                        {@link IDependencyInfo#allowedInSourceFile()} and reported as an error
     *                        when it is not allowed there.
     */
    private List<IDependencyInfo> scanLines(ScanState state, String sourcePath, String content, boolean sourceFileMode) {
        List<IDependencyInfo> dependencies = new ArrayList<>();

        ScanContext ctx = new ScanContext(state, sourcePath);

        String[] lines = content.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int commentIdx = line.indexOf('#');
            if (commentIdx >= 0) {
                line = line.substring(0, commentIdx).trim();
            }
            if (line.isEmpty()) continue;

            ctx.setLineNumber(i + 1);

            for (IDependencyScanHandler handler : handlers) {
                Matcher matcher = handler.pattern().matcher(line);
                if (matcher.matches()) {
                    handler.handleMatch(matcher, ctx);
                    break;
                }
            }
        }

        // Collect dependencies and validate source-file mode
        for (IDependencyInfo dep : ctx.collectedDependencies()) {
            if (sourceFileMode && !dep.allowedInSourceFile()) {
                diagnostics.reportError(
                        ".SOURCE files must not contain " + dep.directiveName() + " directives.",
                        sourcePath, 0);
            } else {
                dependencies.add(dep);
            }
        }

        return dependencies;
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
     * Topological sort using Kahn's algorithm.
     */
    private List<ModuleDescriptor> topologicalSort(ScanState state) {
        Map<ModuleId, ModuleDescriptor> descriptors = state.descriptors;
        Map<ModuleId, Set<ModuleId>> dependencies = new LinkedHashMap<>();
        Map<ModuleId, Set<ModuleId>> dependents = new LinkedHashMap<>();

        for (ModuleDescriptor desc : descriptors.values()) {
            dependencies.put(desc.id(), new LinkedHashSet<>());
            dependents.computeIfAbsent(desc.id(), k -> new LinkedHashSet<>());
        }

        for (ModuleDescriptor desc : descriptors.values()) {
            for (IDependencyInfo dep : desc.dependencies()) {
                ModuleId depId = dep.resolvedModuleId();
                if (depId != null && descriptors.containsKey(depId)) {
                    dependencies.get(desc.id()).add(depId);
                    dependents.computeIfAbsent(depId, k -> new LinkedHashSet<>()).add(desc.id());
                }
            }
        }

        Queue<ModuleId> ready = new ArrayDeque<>();
        for (Map.Entry<ModuleId, Set<ModuleId>> entry : dependencies.entrySet()) {
            if (entry.getValue().isEmpty()) {
                ready.add(entry.getKey());
            }
        }

        List<ModuleDescriptor> sorted = new ArrayList<>();
        while (!ready.isEmpty()) {
            ModuleId current = ready.poll();
            sorted.add(descriptors.get(current));
            for (ModuleId dependent : dependents.getOrDefault(current, Set.of())) {
                dependencies.get(dependent).remove(current);
                if (dependencies.get(dependent).isEmpty()) {
                    ready.add(dependent);
                }
            }
        }

        if (sorted.size() != descriptors.size()) {
            diagnostics.reportError("Circular dependency detected among modules.", "", 0);
        }

        return sorted;
    }

    /**
     * Inner context implementation passed to handlers during scanning.
     */
    private class ScanContext implements IDependencyScanContext {
        private final ScanState state;
        private final String sourcePath;
        private int lineNumber;
        private final List<IDependencyInfo> collected = new ArrayList<>();

        ScanContext(ScanState state, String sourcePath) {
            this.state = state;
            this.sourcePath = sourcePath;
        }

        void setLineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
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
            return DependencyScanner.this.loadContent(resolvedPath);
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
        public void scanNestedModule(String resolvedPath, String content) {
            DependencyScanner.this.scanModule(state, new ModuleId(resolvedPath), resolvedPath, content);
        }

        @Override
        public void scanNestedSourceFile(String resolvedPath, String content) {
            DependencyScanner.this.scanSourceFile(state, resolvedPath, content);
        }

        @Override
        public void addDependency(IDependencyInfo info) {
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
    }
}
