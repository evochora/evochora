package org.evochora.compiler.frontend.module;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.util.SourceLoader;
import org.evochora.compiler.frontend.semantics.ModuleId;
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
 */
public final class DependencyScanner {

    private final DiagnosticsEngine diagnostics;
    private final SourceRootResolver resolver;
    private final List<IDependencyScanHandler> handlers;
    private final Map<ModuleId, ModuleDescriptor> descriptors = new LinkedHashMap<>();
    private final Set<ModuleId> visiting = new LinkedHashSet<>();
    private final Map<String, String> sourceContents = new LinkedHashMap<>();

    public DependencyScanner(DiagnosticsEngine diagnostics, SourceRootResolver resolver, List<IDependencyScanHandler> handlers) {
        this.diagnostics = diagnostics;
        this.resolver = resolver;
        this.handlers = handlers;
    }

    /**
     * Scans the main file and all its transitive dependencies, building a dependency graph.
     */
    public DependencyGraph scan(String mainContent, String mainPath) {
        ModuleId mainId = new ModuleId(mainPath);
        scanModule(mainId, mainPath, mainContent);

        if (diagnostics.hasErrors()) {
            return new DependencyGraph(List.of());
        }

        List<ModuleDescriptor> sorted = topologicalSort();
        return new DependencyGraph(sorted);
    }

    /**
     * Returns all .SOURCE file contents collected during scanning, keyed by resolved path.
     */
    public Map<String, String> sourceContents() {
        return Collections.unmodifiableMap(sourceContents);
    }

    private void scanModule(ModuleId moduleId, String sourcePath, String content) {
        if (descriptors.containsKey(moduleId)) return;

        if (visiting.contains(moduleId)) {
            diagnostics.reportError("Circular dependency detected: " + moduleId.path(), sourcePath, 0);
            return;
        }
        visiting.add(moduleId);

        List<IDependencyInfo> dependencies = scanLines(sourcePath, content, false);
        ModuleDescriptor descriptor = new ModuleDescriptor(moduleId, sourcePath, content, dependencies);
        descriptors.put(moduleId, descriptor);
        visiting.remove(moduleId);
    }

    /**
     * Scans a .SOURCE file for nested directives. Only .SOURCE is valid;
     * any other directive match is reported as an error.
     */
    private void scanSourceFile(String sourcePath, String content) {
        scanLines(sourcePath, content, true);
    }

    /**
     * Core line-by-line scanning with generic handler dispatch.
     * @param sourceFileMode If true, only SourceDependencyInfo is valid — other dependencies trigger errors.
     */
    private List<IDependencyInfo> scanLines(String sourcePath, String content, boolean sourceFileMode) {
        List<IDependencyInfo> dependencies = new ArrayList<>();

        ScanContext ctx = new ScanContext(sourcePath);

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
    private List<ModuleDescriptor> topologicalSort() {
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
        private final String sourcePath;
        private int lineNumber;
        private final List<IDependencyInfo> collected = new ArrayList<>();

        ScanContext(String sourcePath) {
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
            sourceContents.put(resolvedPath, content);
        }

        @Override
        public void reportError(String message) {
            diagnostics.reportError(message, sourcePath, lineNumber);
        }

        @Override
        public void scanNestedModule(String resolvedPath, String content) {
            DependencyScanner.this.scanModule(new ModuleId(resolvedPath), resolvedPath, content);
        }

        @Override
        public void scanNestedSourceFile(String resolvedPath, String content) {
            DependencyScanner.this.scanSourceFile(resolvedPath, content);
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
