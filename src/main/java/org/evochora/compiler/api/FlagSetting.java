package org.evochora.compiler.api;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * What the configuration decides about one preprocessor flag: the flag is switched off, or it is
 * set, with an integer value or without one. Two records, so that whoever reads a setting
 * switches over them exhaustively. A flag the configuration does not name has no setting. What
 * each state means for the directives of a program is the directives' rule, described in
 * {@code docs/ASSEMBLY_SPEC.md} under Conditional Compilation.
 */
public sealed interface FlagSetting permits FlagSetting.Off, FlagSetting.Set {

    /**
     * Returns the setting of a flag the configuration switched off.
     *
     * @return The setting of a flag that is switched off.
     */
    static FlagSetting off() {
        return Off.INSTANCE;
    }

    /**
     * Returns the setting of a flag set without a value.
     *
     * @return The setting of a flag set without a value.
     */
    static FlagSetting set() {
        return Set.WITHOUT_VALUE;
    }

    /**
     * Returns the setting of a flag set with a value.
     *
     * @param value The value of the flag.
     * @return The setting of a flag set with that value.
     */
    static FlagSetting set(long value) {
        return new Set(OptionalLong.of(value));
    }

    /**
     * The setting of a flag the configuration switched off.
     */
    record Off() implements FlagSetting {
        private static final Off INSTANCE = new Off();
    }

    /**
     * The setting of a flag the configuration set.
     *
     * @param value The value of the flag, or empty for a flag set without one.
     */
    record Set(OptionalLong value) implements FlagSetting {
        private static final Set WITHOUT_VALUE = new Set(OptionalLong.empty());

        /**
         * Checks the setting.
         *
         * @throws NullPointerException if {@code value} is null.
         */
        public Set {
            Objects.requireNonNull(value, "value");
        }
    }
}
