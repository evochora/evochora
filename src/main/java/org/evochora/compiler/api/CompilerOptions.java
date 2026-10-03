package org.evochora.compiler.api;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Configuration options for the compiler, controlling path resolution and other settings.
 *
 * @param sourceRoots Ordered list of source root directories for path resolution.
 * @param defines     Preprocessor flags of the compilation: each key is a flag name, each value
 *                    the flag's integer value, or empty for a flag set without a value. A name
 *                    absent from the map is not set. The compact constructor upper-cases the
 *                    names and keeps an unmodifiable copy, so that two options whose flags differ
 *                    only in the case of a name are equal. The syntax of a name is not checked
 *                    here; that is left to whoever reads the flags.
 */
public record CompilerOptions(List<SourceRoot> sourceRoots, Map<String, OptionalInt> defines) {

    /**
     * Normalises the flags: upper-cases every name and copies the map.
     *
     * @throws IllegalArgumentException if two names are the same name after upper-casing; the
     *                                  message names both.
     * @throws NullPointerException     if {@code defines}, one of its names or one of its values
     *                                  is null.
     */
    public CompilerOptions {
        Map<String, OptionalInt> normalised = new HashMap<>();
        Map<String, String> originalNames = new HashMap<>();
        for (Map.Entry<String, OptionalInt> entry : defines.entrySet()) {
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            String previous = originalNames.putIfAbsent(name, entry.getKey());
            if (previous != null) {
                throw new IllegalArgumentException("Defines '" + previous + "' and '" + entry.getKey()
                        + "' are the same flag " + name + "; flag names are case-insensitive");
            }
            normalised.put(name, entry.getValue());
        }
        defines = Map.copyOf(normalised);
    }

    /**
     * Creates options with the given source roots and no preprocessor flags.
     *
     * @param sourceRoots Ordered list of source root directories for path resolution.
     */
    public CompilerOptions(List<SourceRoot> sourceRoots) {
        this(sourceRoots, Map.of());
    }

    /**
     * Creates default compiler options with a single unprefixed root at "." and no preprocessor flags.
     *
     * @return Options whose only source root is the unprefixed path {@code "."}, so every module path
     *         is resolved relative to the working directory and no {@code PREFIX:path} form is available.
     */
    public static CompilerOptions defaults() {
        return new CompilerOptions(List.of(new SourceRoot(".", null)), Map.of());
    }

    private static final java.util.regex.Pattern PREFIX_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]+$");

    /**
     * Validates the configuration: each prefix (including the empty prefix) may appear at most once.
     *
     * @throws IllegalArgumentException if duplicate prefixes or invalid prefix formats are found.
     */
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
