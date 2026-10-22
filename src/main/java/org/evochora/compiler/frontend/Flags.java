package org.evochora.compiler.frontend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The preprocessor flags of one read-time phase: the names that are set, each with an integer
 * value or without one. Names are compared case-insensitively. The flags are global: one instance
 * serves the whole token stream of the preprocessor, another the whole reading of the Lexer
 * phase. The compiler creates both alike, from the flags of the compilation's options and the
 * names nothing may define; the directives of a feature fill them, and a computation at read
 * time reads them.
 * <p>
 * A flag that is set may be defined again only with the same definition, both without a value
 * or both with the same value; {@link #undefine} first is how a flag changes.
 */
public final class Flags {

    private static final String CONFIGURATION = "by the configuration";

    private record Entry(OptionalLong value, String origin) {
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, String> reservedNames;

    private Flags(Map<String, String> reservedNames) {
        this.reservedNames = byKey(reservedNames);
    }

    private static Map<String, String> byKey(Map<String, String> reservedNames) {
        Map<String, String> reserved = new HashMap<>();
        reservedNames.forEach((name, what) -> reserved.put(key(name), what));
        return Map.copyOf(reserved);
    }

    /**
     * Creates the flags as the configuration sets them. The configured names are taken as they
     * are; {@link #configurationErrors} is how the compiler checks them first.
     *
     * @param defines       The flags of the compilation's options, name to optional value.
     * @param reservedNames The names nothing may define, as the symbol table takes them: each
     *                      with what it is, "an operator" or "a function", or the empty text for
     *                      a name reserved as it is, such as {@code @}, the cursor.
     * @return Flags holding exactly the given names and values.
     */
    public static Flags of(Map<String, OptionalLong> defines, Map<String, String> reservedNames) {
        Flags flags = new Flags(reservedNames);
        defines.forEach((name, value) -> flags.entries.put(key(name), new Entry(value, CONFIGURATION)));
        return flags;
    }

    /**
     * Checks the flags the configuration sets: a name that is no flag name, and a name nothing
     * may define, as {@link #define} rejects it.
     *
     * @param defines       The flags of the compilation's options, name to optional value.
     * @param reservedNames The names nothing may define, as {@link #of} takes them.
     * @return One message per name that fails, in the order of the names; empty when all pass.
     */
    public static List<String> configurationErrors(Map<String, OptionalLong> defines,
                                                   Map<String, String> reservedNames) {
        Map<String, String> reserved = byKey(reservedNames);
        List<String> errors = new ArrayList<>();
        defines.keySet().stream().sorted().forEach(name -> {
            String what = reserved.get(key(name));
            if (!isName(name)) {
                errors.add("Configured flag '" + name + "' is not a valid name: a flag name consists of"
                        + " letters, digits and _ and begins with a letter or _");
            } else if (what != null) {
                errors.add(what.isEmpty()
                        ? "Configured flag '" + name + "' cannot be defined."
                        : "Configured flag '" + name + "' is " + what + " and cannot be defined.");
            }
        });
        return errors;
    }

    /**
     * Sets a flag. A reserved name is rejected. A flag that is not set takes the definition; one
     * that is set keeps its definition, and the call is accepted when the definition is the same
     * and rejected when the value differs or one of the two has a value and the other has none.
     *
     * @param name   The flag name, as written.
     * @param value  The value, or empty for a flag without one.
     * @param origin Where the definition stands, as a phrase completing "defined …", e.g.
     *               {@code "at main.evo:3"}; named when a later definition conflicts with it.
     * @return Empty when the definition is accepted, or the message telling why it is not.
     */
    public Optional<String> define(String name, OptionalLong value, String origin) {
        String key = key(name);
        String reserved = reservedNames.get(key);
        if (reserved != null) {
            return Optional.of(reserved.isEmpty()
                    ? "'" + name + "' cannot be defined."
                    : "'" + name + "' is " + reserved + " and cannot be defined.");
        }
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
    public OptionalLong valueOf(String name) {
        Entry entry = entries.get(key(name));
        return entry == null ? OptionalLong.empty() : entry.value();
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

    private static String describe(OptionalLong value) {
        return value.isPresent() ? "as " + value.getAsLong() : "without a value";
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
