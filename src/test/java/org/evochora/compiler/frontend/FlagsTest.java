package org.evochora.compiler.frontend;

import org.evochora.compiler.api.FlagSetting;
import org.evochora.compiler.model.expression.ReservedNames;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flag store with the three states of a name: set, switched off by the configuration, and
 * free. A flag that is switched off is not set and cannot be defined, {@code .UNDEF} frees it,
 * and the option word of {@code .DEFINE} is no flag name.
 */
@Tag("unit")
class FlagsTest {

    private static final ReservedNames RESERVED = ReservedNames.of(Map.of("MIN", "a function"), Set.of("@"));
    private static final String ORIGIN = "at main.evo:1";

    @Test
    void aFlagTheConfigurationSwitchedOffIsOffAndNotSet() {
        Flags flags = Flags.of(Map.of("SHELL", FlagSetting.off(), "ROWS", FlagSetting.set(4)), RESERVED);

        assertThat(flags.isOff("shell")).isTrue();
        assertThat(flags.isSet("SHELL")).isFalse();
        assertThat(flags.definitionOf("SHELL")).isEmpty();
        assertThat(flags.isOff("ROWS")).isFalse();
        assertThat(flags.isSet("ROWS")).isTrue();
        assertThat(flags.isOff("FREE")).isFalse();
    }

    @Test
    void aFlagThatIsSwitchedOffCannotBeDefined() {
        Flags flags = Flags.of(Map.of("SHELL", FlagSetting.off()), RESERVED);

        assertThat(flags.define("shell", OptionalLong.empty(), ORIGIN)).contains(
                "Cannot define flag SHELL: the configuration switched it off; a default the configuration"
                        + " may override is written .DEFINE DEFAULT SHELL, and .UNDEF SHELL frees it");
        assertThat(flags.isSet("SHELL")).isFalse();
        assertThat(flags.isOff("SHELL")).isTrue();
    }

    @Test
    void undefineFreesAFlagThatIsSwitchedOff() {
        Flags flags = Flags.of(Map.of("SHELL", FlagSetting.off()), RESERVED);

        flags.undefine("SHELL");

        assertThat(flags.isOff("SHELL")).isFalse();
        assertThat(flags.define("SHELL", OptionalLong.of(2), ORIGIN)).isEmpty();
        assertThat(flags.valueOf("SHELL")).hasValue(2);
    }

    @Test
    void theOptionWordOfDefineIsNoFlagName() {
        Flags flags = Flags.of(Map.of(), RESERVED);

        assertThat(flags.define("Default", OptionalLong.empty(), ORIGIN))
                .contains("'Default' is the default option of .DEFINE and cannot be defined.");
        assertThat(flags.isSet("DEFAULT")).isFalse();
        assertThat(Flags.configurationErrors(Map.of("default", FlagSetting.set()), RESERVED))
                .containsExactly("Configured flag 'default' is the default option of .DEFINE and cannot be defined.");
    }

    @Test
    void aReservedNameIsRejectedAsBefore() {
        Flags flags = Flags.of(Map.of(), RESERVED);

        assertThat(flags.define("MIN", OptionalLong.empty(), ORIGIN))
                .contains("'MIN' is a function and cannot be defined.");
        assertThat(Flags.configurationErrors(Map.of("min", FlagSetting.off(), "1X", FlagSetting.set()), RESERVED))
                .containsExactly(
                        "Configured flag '1X' is not a valid name: a flag name consists of letters, digits and _"
                                + " and begins with a letter or _",
                        "Configured flag 'min' is a function and cannot be defined.");
    }
}
