package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.Flags;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * The rules of {@code .DEFINE} for a flag that is named already, for both read-time phases. A
 * definition may set a flag that is set only with the same definition, both without a value or
 * both with the same value, and it cannot set a flag the configuration switched off;
 * {@code .UNDEF} first is how a flag changes. A default, the {@code DEFAULT} option, yields to
 * whatever is decided about the flag already: a flag that is set keeps its definition, a flag the
 * configuration switched off stays off.
 */
final class FlagDefinition {

    /**
     * The option word of {@code .DEFINE} that makes the definition a default. Like the words of
     * the other directives it is read by its position, as the first operand; a flag of that name
     * is not defined by {@code .DEFINE}, but the name is not reserved.
     */
    static final String DEFAULT_OPTION = "DEFAULT";

    private FlagDefinition() {
    }

    /**
     * Reports whether the operands of a {@code .DEFINE} begin with the {@code DEFAULT} option,
     * which makes the definition a default of the flag named after it.
     *
     * @param operands The operands of the directive.
     * @return {@code true} when the first operand is the name {@code DEFAULT}, in any case.
     */
    static boolean isDefault(List<Token> operands) {
        return !operands.isEmpty() && Condition.isNameToken(operands.get(0))
                && operands.get(0).text().equalsIgnoreCase(DEFAULT_OPTION);
    }

    /**
     * Defines a flag by the rule: a flag that is free takes the definition, one the configuration
     * switched off rejects it, and one that is set keeps its own, accepting the definition when
     * it is the same and rejecting it when the value differs or one of the two has a value and
     * the other has none.
     *
     * @param flags  The flags of the phase.
     * @param name   The flag name, as written.
     * @param value  The value, or empty for a flag without one.
     * @param origin Where the definition stands, as a phrase completing "defined …", e.g.
     *               {@code "at main.evo:3"}.
     * @return Empty when the definition is accepted, or the message telling why it is not.
     */
    static Optional<String> define(Flags flags, String name, OptionalLong value, String origin) {
        if (flags.isOff(name)) {
            String upper = name.toUpperCase(Locale.ROOT);
            return Optional.of("Cannot define flag " + upper + ": the configuration switched it off;"
                    + " a default the configuration may override is written .DEFINE DEFAULT " + upper
                    + ", and .UNDEF " + upper + " frees it");
        }
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

    /**
     * Defines a flag as a default: a flag that is free takes the definition, a flag that is set
     * or switched off is left as it is. The value is computed only for a flag that is free, so
     * that a default that yields reports nothing, whatever its value would be.
     *
     * @param flags  The flags of the phase.
     * @param name   The flag name, as written.
     * @param value  Computes the value: the value, or empty for a flag without one; or nothing
     *               when the value cannot be computed, which the computation reports itself.
     * @param origin Where the definition stands, as a phrase completing "defined …", e.g.
     *               {@code "at main.evo:3"}.
     * @return Empty when the default is accepted, whether it took effect or yielded, or when its
     *         value cannot be computed; otherwise the message telling why it is not.
     */
    static Optional<String> defineDefault(Flags flags, String name, Supplier<Optional<OptionalLong>> value,
                                          String origin) {
        if (flags.isSet(name) || flags.isOff(name)) {
            return Optional.empty();
        }
        return value.get().flatMap(v -> flags.define(name, v, origin));
    }

    private static String describe(OptionalLong value) {
        return value.isPresent() ? "as " + value.getAsLong() : "without a value";
    }
}
