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
 * @param values      Values of the compilation: each key is a value name, each value the text of
 *                    an operand that the main program's {@code .REQUIRE VALUE} of that name takes
 *                    in place of its default. A value no {@code .REQUIRE VALUE} of the main
 *                    program names is not used. The compact constructor upper-cases the names and
 *                    keeps an unmodifiable copy, as it does for the flags. The text is not checked
 *                    here; the compiler reports a text that is not one operand.
 */
public record CompilerOptions(List<SourceRoot> sourceRoots, Map<String, OptionalInt> defines,
                              Map<String, String> values) {

    /**
     * Normalises the flags and the values: upper-cases every name and copies the maps.
     *
     * @throws IllegalArgumentException if two names of the flags, or two names of the values, are
     *                                  the same name after upper-casing; the message names both.
     * @throws NullPointerException     if {@code defines} or {@code values}, one of their names or
     *                                  one of their values is null.
     */
    public CompilerOptions {
        defines = upperCased(defines, "Defines", "flag");
        values = upperCased(values, "Values", "value");
    }

    /**
     * Creates options with the given source roots, no preprocessor flags and no values.
     *
     * @param sourceRoots Ordered list of source root directories for path resolution.
     */
    public CompilerOptions(List<SourceRoot> sourceRoots) {
        this(sourceRoots, Map.of(), Map.of());
    }

    /**
     * Copies a map of names with every name upper-cased.
     *
     * @param plural   What the map holds, capitalised, for the message: {@code Defines}, {@code Values}.
     * @param singular What one name names, for the message: {@code flag}, {@code value}.
     * @throws IllegalArgumentException if two names are the same name after upper-casing.
     */
    private static <V> Map<String, V> upperCased(Map<String, V> byName, String plural, String singular) {
        Map<String, V> normalised = new HashMap<>();
        Map<String, String> originalNames = new HashMap<>();
        for (Map.Entry<String, V> entry : byName.entrySet()) {
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            String previous = originalNames.putIfAbsent(name, entry.getKey());
            if (previous != null) {
                throw new IllegalArgumentException(plural + " '" + previous + "' and '" + entry.getKey()
                        + "' are the same " + singular + " " + name + "; " + singular + " names are case-insensitive");
            }
            normalised.put(name, entry.getValue());
        }
        return Map.copyOf(normalised);
    }

    /**
     * Creates default compiler options with a single unprefixed root at "." and no preprocessor
     * flags or values.
     *
     * @return Options whose only source root is the unprefixed path {@code "."}, so every module path
     *         is resolved relative to the working directory and no {@code PREFIX:path} form is available.
     */
    public static CompilerOptions defaults() {
        return new CompilerOptions(List.of(new SourceRoot(".", null)), Map.of(), Map.of());
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
