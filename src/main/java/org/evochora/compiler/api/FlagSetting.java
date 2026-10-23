package org.evochora.compiler.api;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * What the configuration says about one preprocessor flag: the flag is switched off, set without
 * a value, or set with an integer value. A flag the configuration does not name has no setting.
 *
 * <p>A flag that is set counts as set for {@code .IFDEF} and {@code .IFNDEF}; a program may
 * redefine it with {@code .DEFINE}. A flag that is switched off counts as not set for
 * {@code .IFDEF} and {@code .IFNDEF}, and a program cannot set it with {@code .DEFINE};
 * {@code .UNDEF} frees it, after which the program may define it as any flag the configuration
 * does not name.</p>
 *
 * @param isSet {@code true} if the flag is set, {@code false} if it is switched off.
 * @param value The value of a flag that is set, or empty for a flag set without one; always empty
 *              for a flag that is switched off.
 */
public record FlagSetting(boolean isSet, OptionalLong value) {

    private static final FlagSetting OFF = new FlagSetting(false, OptionalLong.empty());
    private static final FlagSetting SET = new FlagSetting(true, OptionalLong.empty());

    /**
     * Checks the setting.
     *
     * @throws NullPointerException     if {@code value} is null.
     * @throws IllegalArgumentException if a flag that is switched off carries a value.
     */
    public FlagSetting {
        Objects.requireNonNull(value, "value");
        if (!isSet && value.isPresent()) {
            throw new IllegalArgumentException("A flag that is switched off carries no value");
        }
    }

    /**
     * Returns the setting of a flag the configuration switched off: it counts as not set for
     * {@code .IFDEF} and {@code .IFNDEF}, and the program cannot set it with {@code .DEFINE}
     * unless {@code .UNDEF} frees it first.
     *
     * @return The setting of a flag that is switched off.
     */
    public static FlagSetting off() {
        return OFF;
    }

    /**
     * Returns the setting of a flag set without a value: it counts as set for {@code .IFDEF} and
     * {@code .IFNDEF}.
     *
     * @return The setting of a flag set without a value.
     */
    public static FlagSetting set() {
        return SET;
    }

    /**
     * Returns the setting of a flag set with a value: it counts as set for {@code .IFDEF} and
     * {@code .IFNDEF}, and a computation at read time reads the value.
     *
     * @param value The value of the flag.
     * @return The setting of a flag set with that value.
     */
    public static FlagSetting set(long value) {
        return new FlagSetting(true, OptionalLong.of(value));
    }
}
