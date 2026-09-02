package org.evochora.compiler.api;

/**
 * Defines a source root directory for the compiler's path resolution.
 * Source roots are base directories from which the compiler resolves module files.
 *
 * @param path   The directory path (absolute or relative to working directory).
 * @param prefix An optional namespace prefix (e.g., "PRED", "PREY"). Null means default (unprefixed) root.
 */
public record SourceRoot(String path, String prefix) {

    /**
     * Returns whether this is the default (unprefixed) root.
     *
     * @return {@code true} if the prefix is {@code null} or empty — both forms mean the same thing —
     *         so this root is the one used for paths written without a {@code PREFIX:} qualifier.
     */
    public boolean isDefault() {
        return prefix == null || prefix.isEmpty();
    }
}
