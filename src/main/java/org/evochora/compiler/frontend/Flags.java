package org.evochora.compiler.frontend;

import org.evochora.compiler.api.FlagSetting;
import org.evochora.compiler.model.expression.ReservedNames;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The preprocessor flags of one read-time phase. A name is in one of three states: set, with an
 * integer value or without one; switched off by the configuration; or free, neither set nor
 * switched off. A flag that is switched off counts as not set; what a directive may do to it is
 * the directive's rule. Names are compared case-insensitively. The flags are global: one
 * instance serves the whole token stream of the preprocessor, another the whole reading of the Lexer
 * phase. The compiler creates both alike, from the flags of the compilation's options and the
 * names nothing may define; the directives of a feature fill them, and a computation at read
 * time reads them. What a directive may do to a flag that is set already is the directive's
 * rule; the flags hold each definition with where it stands, so that the rule can name it.
 */
public final class Flags {

    private static final String CONFIGURATION = "by the configuration";

    /**
     * The definition of a flag that is set.
     *
     * @param value  The value, or empty for a flag set without one.
     * @param origin Where the definition stands, as a phrase completing "defined …", e.g.
     *               {@code "at main.evo:3"} or {@code "by the configuration"}.
     */
    public record Definition(OptionalLong value, String origin) {
    }

    private final Map<String, Definition> entries = new HashMap<>();
    private final Set<String> off = new HashSet<>();
    private final ReservedNames reservedNames;

    private Flags(ReservedNames reservedNames) {
        this.reservedNames = reservedNames;
    }

    /**
     * Creates the flags as the configuration names them: a flag the configuration sets is set,
     * with its value, a flag it switches off is switched off, and every other name is free. The
     * configured names are taken as they are; {@link #configurationErrors} is how the compiler
     * checks them first.
     *
     * @param defines       The flags of the compilation's options, name to setting.
     * @param reservedNames The names nothing may define.
     * @return Flags holding exactly the given names in the given states.
     */
    public static Flags of(Map<String, FlagSetting> defines, ReservedNames reservedNames) {
        Flags flags = new Flags(reservedNames);
        defines.forEach((name, setting) -> {
            switch (setting) {
                case FlagSetting.Set set -> flags.entries.put(key(name), new Definition(set.value(), CONFIGURATION));
                case FlagSetting.Off off -> flags.off.add(key(name));
            }
        });
        return flags;
    }

    /**
     * Checks the flags the configuration names: a name that is no flag name, and a name nothing
     * may define, as {@link #define} rejects it.
     *
     * @param defines       The flags of the compilation's options, name to setting.
     * @param reservedNames The names nothing may define.
     * @return One message per name that fails, in the order of the names; empty when all pass.
     */
    public static List<String> configurationErrors(Map<String, FlagSetting> defines,
                                                   ReservedNames reservedNames) {
        List<String> errors = new ArrayList<>();
        defines.keySet().stream().sorted().forEach(name -> {
            if (!isName(name)) {
                errors.add("Configured flag '" + name + "' is not a valid name: a flag name consists of"
                        + " letters, digits and _ and begins with a letter or _");
            } else {
                reservedNames.rejection(name, "defined").ifPresent(rejection -> errors.add("Configured flag " + rejection));
            }
        });
        return errors;
    }

    /**
     * Sets a flag, in place of a definition it has or of its switched-off state. A reserved name
     * is rejected.
     *
     * @param name   The flag name, as written.
     * @param value  The value, or empty for a flag without one.
     * @param origin Where the definition stands, as a phrase completing "defined …", e.g.
     *               {@code "at main.evo:3"}.
     * @return Empty when the flag is set, or the message telling why it is not.
     */
    public Optional<String> define(String name, OptionalLong value, String origin) {
        Optional<String> rejection = reservedNames.rejection(name, "defined");
        if (rejection.isPresent()) {
            return rejection;
        }
        off.remove(key(name));
        entries.put(key(name), new Definition(value, origin));
        return Optional.empty();
    }

    /**
     * Returns the definition of a flag.
     *
     * @param name The flag name.
     * @return The definition, or empty if the flag is not set.
     */
    public Optional<Definition> definitionOf(String name) {
        return Optional.ofNullable(entries.get(key(name)));
    }

    /**
     * Frees a flag: a flag that is set is no longer set, and a flag the configuration switched
     * off is no longer switched off, as if the configuration had never named it. A flag that is
     * free stays free; that is no error.
     *
     * @param name The flag name.
     */
    public void undefine(String name) {
        entries.remove(key(name));
        off.remove(key(name));
    }

    /**
     * Reports whether the configuration switched a flag off and nothing has freed it since. Such
     * a flag is not set.
     *
     * @param name The flag name.
     * @return {@code true} if the flag is switched off.
     */
    public boolean isOff(String name) {
        return off.contains(key(name));
    }

    /**
     * Reports whether a flag is set, with or without a value. A flag that is switched off is not
     * set.
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
        Definition entry = entries.get(key(name));
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

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
