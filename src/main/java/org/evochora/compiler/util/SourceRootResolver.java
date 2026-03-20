package org.evochora.compiler.util;

import org.evochora.compiler.api.SourceRoot;


import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves directive paths ({@code .IMPORT}, {@code .SOURCE}) against configured source roots.
 * Replaces the previous parent-file-relative resolution with source-root-relative resolution.
 *
 * <p>Supports an optional PREFIX:path syntax where PREFIX identifies a named source root
 * (e.g., {@code PRED:main.evo} resolves against the source root with prefix "PRED").</p>
 */
public final class SourceRootResolver {

    // Prefix must be at least 2 characters to avoid collision with Windows drive letters (C:\)
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^([A-Z][A-Z0-9_]+):(.+)$");

    private final List<SourceRoot> sourceRoots;
    private final Path workingDirectory;

    /**
     * @param sourceRoots      The configured source roots.
     * @param workingDirectory Base directory for resolving relative source root paths.
     */
    public SourceRootResolver(List<SourceRoot> sourceRoots, Path workingDirectory) {
        this.sourceRoots = sourceRoots;
        this.workingDirectory = workingDirectory;
    }

    /**
     * Result of parsing a PREFIX:path string.
     *
     * @param prefix   The prefix, or null if unprefixed.
     * @param filePath The file path portion.
     */
    public record ParsedPath(String prefix, String filePath) {}

    /**
     * Parses a directive path into optional prefix and file path.
     * HTTP URLs pass through without prefix extraction.
     * Prefixes must match {@code [A-Z][A-Z0-9_]*} to avoid collision with
     * Windows drive letters (e.g., {@code C:\}).
     *
     * @param directivePath The raw path from a directive (e.g., "PRED:lib/move.evo").
     * @return The parsed prefix and file path.
     */
    public static ParsedPath parsePath(String directivePath) {
        if (SourceLoader.isHttpUrl(directivePath)) {
            return new ParsedPath(null, directivePath);
        }
        var matcher = PREFIX_PATTERN.matcher(directivePath);
        if (matcher.matches()) {
            return new ParsedPath(matcher.group(1), matcher.group(2));
        }
        return new ParsedPath(null, directivePath);
    }

    /**
     * Resolves a directive path against the configured source roots.
     * HTTP URLs pass through unchanged. Prefixed paths resolve against their named root.
     * Unprefixed paths resolve against the default root.
     *
     * @param directivePath  The path from the directive (may contain PREFIX:).
     * @param sourceFilePath The file containing the directive (unused for resolution,
     *                       retained for HTTP relative resolution).
     * @return The resolved absolute path.
     */
    public String resolve(String directivePath, String sourceFilePath) {
        if (SourceLoader.isHttpUrl(directivePath)) {
            return directivePath;
        }
        if (SourceLoader.isHttpUrl(sourceFilePath)) {
            return SourceLoader.resolveHttpRelative(sourceFilePath, directivePath);
        }

        ParsedPath parsed = parsePath(directivePath);
        SourceRoot root = findRoot(parsed.prefix());

        if (SourceLoader.isHttpUrl(root.path())) {
            String base = root.path().endsWith("/") ? root.path() : root.path() + "/";
            return SourceLoader.resolveHttpRelative(base, parsed.filePath());
        }

        Path rootPath = workingDirectory.resolve(root.path()).normalize();
        return rootPath.resolve(parsed.filePath()).normalize().toString().replace('\\', '/');
    }

    private SourceRoot findRoot(String prefix) {
        if (prefix == null) {
            return sourceRoots.stream()
                    .filter(SourceRoot::isDefault)
                    .findFirst()
                    .orElseThrow(() -> new UnknownPrefixException(
                            "No source root configured for unprefixed paths. "
                            + "Add a source root without prefix or qualify the path with a prefix (e.g., MYLIB:path.evo)."));
        }
        return sourceRoots.stream()
                .filter(r -> prefix.equals(r.prefix()))
                .findFirst()
                .orElseThrow(() -> new UnknownPrefixException(
                        "No source root configured for prefix '" + prefix + "'. "
                        + "Available prefixes: " + availablePrefixes()));
    }

    private String availablePrefixes() {
        var prefixes = sourceRoots.stream()
                .map(r -> r.isDefault() ? "(default)" : r.prefix())
                .toList();
        return prefixes.isEmpty() ? "(none)" : String.join(", ", prefixes);
    }

    /**
     * Thrown when a directive path references a prefix with no matching source root.
     */
    public static class UnknownPrefixException extends RuntimeException {
        public UnknownPrefixException(String message) {
            super(message);
        }
    }
}
