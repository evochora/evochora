package org.evochora.compiler.api;

import java.util.List;

/**
 * Configuration options for the compiler, controlling path resolution and other settings.
 *
 * @param sourceRoots Ordered list of source root directories for path resolution.
 */
public record CompilerOptions(List<SourceRoot> sourceRoots) {

    /**
     * Creates default compiler options with a single unprefixed root at ".".
     */
    public static CompilerOptions defaults() {
        return new CompilerOptions(List.of(new SourceRoot(".", null)));
    }

    /**
     * Validates the configuration: each prefix (including the empty prefix) may appear at most once.
     *
     * @throws IllegalArgumentException if duplicate prefixes are found.
     */
    public void validate() {
        // Normalize null and "" to the same key for duplicate detection
        long distinctPrefixes = sourceRoots.stream()
                .map(r -> r.isDefault() ? "" : r.prefix())
                .distinct()
                .count();
        if (distinctPrefixes != sourceRoots.size()) {
            throw new IllegalArgumentException("Duplicate source root prefixes detected");
        }
    }
}
