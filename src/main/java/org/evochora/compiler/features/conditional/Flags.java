package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.module.IDependencyScanContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The preprocessor flags of one compilation: the names that are set, each with an integer value
 * or without one. Names are compared case-insensitively. The flags are global: one instance
 * serves the whole token stream of the preprocessor, another the whole text of the dependency
 * scan, each kept in the state slot of its phase's context and seeded from the flags of the
 * compilation's options on first use.
 * <p>
 * A flag that is set may be defined again only with the same definition, both without a value
 * or both with the same value; {@link #undefine} first is how a flag changes.
 */
public final class Flags {

    private static final String CONFIGURATION = "by the configuration";

    private record Entry(OptionalInt value, String origin) {
    }

    private final Map<String, Entry> entries = new HashMap<>();

    private Flags() {
    }

    /**
     * Creates the flags as the configuration sets them. The names are taken as they are, valid
     * or not; a name that is no identifier can never be named by a directive.
     *
     * @param defines The flags of the compilation's options, name to optional value.
     * @return Flags holding exactly the given names and values.
     */
    public static Flags of(Map<String, OptionalInt> defines) {
        Flags flags = new Flags();
        defines.forEach((name, value) -> flags.entries.put(key(name), new Entry(value, CONFIGURATION)));
        return flags;
    }

    /**
     * Returns the flags of the preprocessor run, seeding them from the options on the first
     * request. The seeding reports every configured name that is not an identifier, once per
     * run, against the configuration rather than a source line.
     *
     * @param preProcessor The preprocessor, for its diagnostics.
     * @param context      The context holding the slot and the options.
     * @return The one instance of the run.
     */
    public static Flags inPreprocessor(PreProcessor preProcessor, PreProcessorContext context) {
        return context.getOrCreate(Flags.class, () -> {
            Map<String, OptionalInt> defines = context.options().defines();
            defines.keySet().stream().filter(name -> !isName(name)).sorted().forEach(name ->
                    preProcessor.getDiagnostics().reportError("Configured flag '" + name
                            + "' is not a valid name: a flag name consists of letters, digits and _"
                            + " and begins with a letter or _", "", 0));
            return of(defines);
        });
    }

    /**
     * Returns the flags of the dependency scan, seeding them silently from the options on the
     * first request; the preprocessor reports what is wrong with them.
     *
     * @param context The scan context holding the slot and the options.
     * @return The one instance of the scan.
     */
    public static Flags inScan(IDependencyScanContext context) {
        return context.getOrCreate(Flags.class, () -> of(context.options().defines()));
    }

    /**
     * Sets a flag. A flag that is not set takes the definition; one that is set keeps its
     * definition, and the call is accepted when the definition is the same and rejected when the
     * value differs or one of the two has a value and the other has none.
     *
     * @param name   The flag name.
     * @param value  The value, or empty for a flag without one.
     * @param origin Where the definition stands, as a phrase completing "defined …", e.g.
     *               {@code "at main.evo:3"}; named when a later definition conflicts with it.
     * @return Empty when the definition is accepted, or the message telling why it is not.
     */
    public Optional<String> define(String name, OptionalInt value, String origin) {
        String key = key(name);
        Entry existing = entries.get(key);
        if (existing == null) {
            entries.put(key, new Entry(value, origin));
            return Optional.empty();
        }
        if (existing.value().equals(value)) {
            return Optional.empty();
        }
        return Optional.of("Cannot define flag " + key + " " + describe(value) + ": it is already defined "
                + describe(existing.value()) + " " + existing.origin() + "; .UNDEF it first to change it");
    }

    /**
     * Removes a flag. A flag that is not set stays unset; that is no error.
     *
     * @param name The flag name.
     */
    public void undefine(String name) {
        entries.remove(key(name));
    }

    /**
     * Reports whether a flag is set, with or without a value.
     *
     * @param name The flag name.
     * @return {@code true} if the flag is set.
     */
    public boolean isSet(String name) {
        return entries.containsKey(key(name));
    }

    /**
     * Returns the value of a flag.
     *
     * @param name The flag name.
     * @return The value, or empty if the flag is not set or set without a value.
     */
    public OptionalInt valueOf(String name) {
        Entry entry = entries.get(key(name));
        return entry == null ? OptionalInt.empty() : entry.value();
    }

    /**
     * Reports whether a text is a flag name by the rule of the lexer's identifiers: letters,
     * digits and {@code _}, beginning with a letter or {@code _}. The lexer accepts a few more
     * characters in an identifier, which make it a register, a directive or a qualified name;
     * none of those is a flag name.
     *
     * @param text The text.
     * @return {@code true} if the text is a flag name.
     */
    public static boolean isName(String text) {
        if (text.isEmpty() || !isLetter(text.charAt(0))) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isLetter(c) && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static String describe(OptionalInt value) {
        return value.isPresent() ? "as " + value.getAsInt() : "without a value";
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
