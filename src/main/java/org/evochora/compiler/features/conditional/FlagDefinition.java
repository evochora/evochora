package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.Flags;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The rule of {@code .DEFINE} for a flag that is set already, for both read-time phases: it may
 * be defined again only with the same definition, both without a value or both with the same
 * value; {@code .UNDEF} first is how a flag changes.
 */
final class FlagDefinition {

    private FlagDefinition() {
    }

    /**
     * Defines a flag by the rule: a flag that is not set takes the definition, one that is set
     * keeps its own, and the definition is accepted when it is the same and rejected when the
     * value differs or one of the two has a value and the other has none.
     *
     * @param flags  The flags of the phase.
     * @param name   The flag name, as written.
     * @param value  The value, or empty for a flag without one.
     * @param origin Where the definition stands, as a phrase completing "defined …", e.g.
     *               {@code "at main.evo:3"}.
     * @return Empty when the definition is accepted, or the message telling why it is not.
     */
    static Optional<String> define(Flags flags, String name, OptionalLong value, String origin) {
        Optional<Flags.Definition> existing = flags.definitionOf(name);
        if (existing.isEmpty()) {
            return flags.define(name, value, origin);
        }
        if (existing.get().value().equals(value)) {
            return Optional.empty();
        }
        return Optional.of("Cannot define flag " + name.toUpperCase(Locale.ROOT) + " " + describe(value)
                + ": it is already defined " + describe(existing.get().value()) + " " + existing.get().origin()
                + "; .UNDEF it first to change it");
    }

    private static String describe(OptionalLong value) {
        return value.isPresent() ? "as " + value.getAsLong() : "without a value";
    }
}
