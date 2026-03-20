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
    private static final java.util.regex.Pattern PREFIX_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]+$");

    public void validate() {
        for (SourceRoot root : sourceRoots) {
            if (root.prefix() != null && !root.prefix().isEmpty()) {
                if (!PREFIX_PATTERN.matcher(root.prefix()).matches()) {
                    throw new IllegalArgumentException(
                            "Source root prefix '" + root.prefix() + "' is invalid — "
                            + "prefixes must be at least 2 uppercase characters ([A-Z][A-Z0-9_]+) "
                            + "to avoid collision with Windows drive letters.");
                }
            }
        }
        long distinctPrefixes = sourceRoots.stream()
                .map(r -> r.isDefault() ? "" : r.prefix())
                .distinct()
                .count();
        if (distinctPrefixes != sourceRoots.size()) {
            throw new IllegalArgumentException("Duplicate source root prefixes detected");
        }
    }
}
