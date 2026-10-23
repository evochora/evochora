package org.evochora.compiler.model.expression;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The names nothing in a program may define, compared without regard to letter case, each with
 * what it is where that tells the programmer why: "an operator" or "a function" for the name of
 * an operation of computations, nothing for a name reserved as it is, such as {@code @}, the
 * cursor. Every place that defines a name rejects a reserved one with the message this gives.
 */
public final class ReservedNames {

    /** No reserved names. */
    public static final ReservedNames NONE = new ReservedNames(Map.of());

    private final Map<String, Optional<String>> names;

    private ReservedNames(Map<String, Optional<String>> names) {
        this.names = Map.copyOf(names);
    }

    /**
     * Creates the reserved names.
     *
     * @param described The names that are something, each with what it is, worded for the
     *                  message, e.g. "an operator".
     * @param plain     The names reserved as they are.
     * @return The reserved names.
     */
    public static ReservedNames of(Map<String, String> described, Set<String> plain) {
        Map<String, Optional<String>> names = new HashMap<>();
        described.forEach((name, what) -> names.put(key(name), Optional.of(what)));
        plain.forEach(name -> names.put(key(name), Optional.empty()));
        return new ReservedNames(names);
    }

    /**
     * Returns the message that rejects a definition of a name, if the name is reserved.
     *
     * @param name The name, as written.
     * @param role What the name would become, completing "cannot be …", e.g. "defined" or
     *             "a macro name".
     * @return The message, or empty if the name is not reserved.
     */
    public Optional<String> rejection(String name, String role) {
        Optional<String> what = names.get(key(name));
        if (what == null) {
            return Optional.empty();
        }
        return Optional.of(what.map(w -> "'" + name + "' is " + w + " and cannot be " + role + ".")
                .orElse("'" + name + "' cannot be " + role + "."));
    }

    /**
     * Returns what a reserved name is.
     *
     * @param name The name, in any letter case.
     * @return What it is, e.g. "a function"; empty if the name is reserved as it is or not at all.
     */
    public Optional<String> description(String name) {
        return names.getOrDefault(key(name), Optional.empty());
    }

    /**
     * Returns the reserved names, upper-cased.
     *
     * @return An unmodifiable set of the names.
     */
    public Set<String> names() {
        return names.keySet();
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
