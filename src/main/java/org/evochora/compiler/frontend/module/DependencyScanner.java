package org.evochora.compiler.frontend.module;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.io.SourceLoader;
import org.evochora.compiler.frontend.semantics.ModuleId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0: Scans source files for module directives ({@code .IMPORT}, {@code .REQUIRE},
 * {@code .SOURCE}) and builds a {@link DependencyGraph}.
 *
 * <p>This is a lightweight text-based scan — it does not invoke the lexer or parser.
 * It loads all referenced files, detects circular dependencies, and produces a
 * topological ordering of modules.</p>
 */
public final class DependencyScanner {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?i)^\\.IMPORT\\s+\"([^\"]+)\"\\s+AS\\s+(\\w+)((?:\\s+USING\\s+\\w+\\s+AS\\s+\\w+)*)\\s*$");
    private static final Pattern REQUIRE_PATTERN = Pattern.compile(
            "(?i)^\\.REQUIRE\\s+\"([^\"]+)\"\\s+AS\\s+(\\w+)\\s*$");
    private static final Pattern SOURCE_PATTERN = Pattern.compile(
            "(?i)^\\.SOURCE\\s+\"([^\"]+)\"\\s*$");
    private static final Pattern USING_PATTERN = Pattern.compile(
            "(?i)USING\\s+(\\w+)\\s+AS\\s+(\\w+)");

    private final DiagnosticsEngine diagnostics;
    private final Map<ModuleId, ModuleDescriptor> descriptors = new LinkedHashMap<>();
    private final Set<ModuleId> visiting = new LinkedHashSet<>();

    public DependencyScanner(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
    }

    /**
     * Scans the main file and all its transitive dependencies, building a dependency graph.
     *
     * @param mainContent  The source content of the main file.
     * @param mainPath     The resolved, normalized path of the main file.
     * @param mainBasePath The directory of the main file (for relative path resolution).
     * @return A dependency graph with modules in topological order.
     */
    public DependencyGraph scan(String mainContent, String mainPath, Path mainBasePath) {
        ModuleId mainId = new ModuleId(mainPath);
        scanModule(mainId, mainPath, mainContent, mainBasePath);

        if (diagnostics.hasErrors()) {
            return new DependencyGraph(List.of());
        }

        // Topological sort (Kahn's algorithm)
        List<ModuleDescriptor> sorted = topologicalSort();
        return new DependencyGraph(sorted);
    }

    private void scanModule(ModuleId moduleId, String sourcePath, String content, Path basePath) {
        if (descriptors.containsKey(moduleId)) return;

        if (visiting.contains(moduleId)) {
            diagnostics.reportError(
                    "Circular dependency detected: " + moduleId.path(),
                    sourcePath, 0);
            return;
        }
        visiting.add(moduleId);

        List<ModuleDescriptor.ImportDecl> imports = new ArrayList<>();
        List<ModuleDescriptor.RequireDecl> requires = new ArrayList<>();
        List<String> sourceFiles = new ArrayList<>();

        String[] lines = content.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            // Skip comments
            int commentIdx = line.indexOf('#');
            if (commentIdx >= 0) {
                line = line.substring(0, commentIdx).trim();
            }
            if (line.isEmpty()) continue;

            Matcher importMatcher = IMPORT_PATTERN.matcher(line);
            if (importMatcher.matches()) {
                String path = importMatcher.group(1);
                String alias = importMatcher.group(2);
                String usingsPart = importMatcher.group(3);

                List<ModuleDescriptor.UsingDecl> usings = new ArrayList<>();
                if (usingsPart != null && !usingsPart.isBlank()) {
                    Matcher usingMatcher = USING_PATTERN.matcher(usingsPart);
                    while (usingMatcher.find()) {
                        usings.add(new ModuleDescriptor.UsingDecl(usingMatcher.group(1), usingMatcher.group(2)));
                    }
                }
                // Recursively scan the imported module
                String resolvedPath = resolvePath(path, sourcePath, basePath);
                ModuleId importId = new ModuleId(resolvedPath);
                imports.add(new ModuleDescriptor.ImportDecl(path, alias, usings, importId));
                try {
                    String importContent = loadContent(resolvedPath, path, sourcePath, basePath);
                    Path importBasePath = deriveBasePath(resolvedPath);
                    scanModule(importId, resolvedPath, importContent, importBasePath);
                } catch (IOException e) {
                    diagnostics.reportError("Could not load imported module: " + path, sourcePath, i + 1);
                }
                continue;
            }

            Matcher requireMatcher = REQUIRE_PATTERN.matcher(line);
            if (requireMatcher.matches()) {
                String path = requireMatcher.group(1);
                String alias = requireMatcher.group(2);
                requires.add(new ModuleDescriptor.RequireDecl(path, alias));
                continue;
            }

            Matcher sourceMatcher = SOURCE_PATTERN.matcher(line);
            if (sourceMatcher.matches()) {
                String path = sourceMatcher.group(1);
                sourceFiles.add(path);
                // Scan .SOURCE files for nested .SOURCE directives and validate no .IMPORT/.REQUIRE
                String resolvedPath = resolvePath(path, sourcePath, basePath);
                try {
                    String sourceContent = loadContent(resolvedPath, path, sourcePath, basePath);
                    scanSourceFile(resolvedPath, sourceContent, basePath);
                } catch (IOException e) {
                    diagnostics.reportError("Could not load sourced file: " + path, sourcePath, i + 1);
                }
            }
        }

        ModuleDescriptor descriptor = new ModuleDescriptor(moduleId, sourcePath, content, imports, requires, sourceFiles);
        descriptors.put(moduleId, descriptor);
        visiting.remove(moduleId);
    }

    /**
     * Scans a .SOURCE file for validation: must not contain .IMPORT or .REQUIRE,
     * but may contain nested .SOURCE.
     */
    private void scanSourceFile(String sourcePath, String content, Path basePath) {
        String[] lines = content.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int commentIdx = line.indexOf('#');
            if (commentIdx >= 0) {
                line = line.substring(0, commentIdx).trim();
            }
            if (line.isEmpty()) continue;

            if (IMPORT_PATTERN.matcher(line).matches()) {
                diagnostics.reportError(".SOURCE files must not contain .IMPORT directives.", sourcePath, i + 1);
            }
            if (REQUIRE_PATTERN.matcher(line).matches()) {
                diagnostics.reportError(".SOURCE files must not contain .REQUIRE directives.", sourcePath, i + 1);
            }

            Matcher sourceMatcher = SOURCE_PATTERN.matcher(line);
            if (sourceMatcher.matches()) {
                String nestedPath = sourceMatcher.group(1);
                String resolvedPath = resolvePath(nestedPath, sourcePath, basePath);
                try {
                    String nestedContent = loadContent(resolvedPath, nestedPath, sourcePath, basePath);
                    scanSourceFile(resolvedPath, nestedContent, basePath);
                } catch (IOException e) {
                    diagnostics.reportError("Could not load sourced file: " + nestedPath, sourcePath, i + 1);
                }
            }
        }
    }

    /**
     * Resolves a path relative to the source file or as an absolute URL.
     */
    private String resolvePath(String path, String sourceFilePath, Path basePath) {
        if (SourceLoader.isHttpUrl(path)) {
            return path;
        }
        if (SourceLoader.isHttpUrl(sourceFilePath)) {
            return SourceLoader.resolveHttpRelative(sourceFilePath, path);
        }
        Path sourceDir = Path.of(sourceFilePath).getParent();
        if (sourceDir == null) sourceDir = basePath;
        return sourceDir.resolve(path).normalize().toString().replace('\\', '/');
    }

    private String loadContent(String resolvedPath, String originalPath, String sourceFilePath, Path basePath) throws IOException {
        if (SourceLoader.isHttpUrl(resolvedPath)) {
            return SourceLoader.loadHttp(resolvedPath).content();
        }
        Path filePath = Path.of(resolvedPath);
        if (Files.exists(filePath)) {
            return SourceLoader.loadFile(filePath).content();
        }
        throw new IOException("File not found: " + resolvedPath);
    }

    private Path deriveBasePath(String resolvedPath) {
        if (SourceLoader.isHttpUrl(resolvedPath)) {
            return Path.of(".");
        }
        Path parent = Path.of(resolvedPath).getParent();
        return parent != null ? parent : Path.of(".");
    }

    /**
     * Topological sort using Kahn's algorithm.
     */
    private List<ModuleDescriptor> topologicalSort() {
        // Build adjacency: module -> set of modules it depends on (imports)
        Map<ModuleId, Set<ModuleId>> dependencies = new LinkedHashMap<>();
        Map<ModuleId, Set<ModuleId>> dependents = new LinkedHashMap<>();

        for (ModuleDescriptor desc : descriptors.values()) {
            dependencies.put(desc.id(), new LinkedHashSet<>());
            dependents.computeIfAbsent(desc.id(), k -> new LinkedHashSet<>());
        }

        for (ModuleDescriptor desc : descriptors.values()) {
            for (ModuleDescriptor.ImportDecl imp : desc.imports()) {
                String resolvedPath = resolvePath(imp.path(), desc.sourcePath(),
                        Path.of(desc.sourcePath()).getParent() != null ?
                                Path.of(desc.sourcePath()).getParent() : Path.of("."));
                ModuleId depId = new ModuleId(resolvedPath);
                if (descriptors.containsKey(depId)) {
                    dependencies.get(desc.id()).add(depId);
                    dependents.computeIfAbsent(depId, k -> new LinkedHashSet<>()).add(desc.id());
                }
            }
        }

        // Kahn's algorithm
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
}
