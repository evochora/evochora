package org.evochora.compiler.features.conditional;

import org.evochora.compiler.api.FlagSetting;
import org.evochora.compiler.frontend.Flags;
import org.evochora.compiler.model.expression.ReservedNames;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules of {@code .DEFINE} against the flag store: a definition of a flag that is set must
 * repeat it, a default yields to a flag that is set or switched off and takes a flag that is
 * free, and neither may bear a reserved name.
 */
@Tag("unit")
class FlagDefinitionTest {

    private static final ReservedNames RESERVED = ReservedNames.of(Map.of("MIN", "a function"), Set.of("@"));
    private static final String ORIGIN = "at main.evo:1";

    @Test
    void aDefaultTakesAFreeFlag() {
        Flags flags = Flags.of(Map.of(), RESERVED);

        assertThat(FlagDefinition.defineDefault(flags, "REDUNDANCY", () -> Optional.of(OptionalLong.of(1)), ORIGIN)).isEmpty();

        assertThat(flags.valueOf("REDUNDANCY")).hasValue(1);
        assertThat(flags.definitionOf("REDUNDANCY").orElseThrow().origin()).isEqualTo(ORIGIN);
    }

    @Test
    void aDefaultYieldsToAFlagTheConfigurationSet() {
        Flags flags = Flags.of(Map.of("REDUNDANCY", FlagSetting.set(2)), RESERVED);

        assertThat(FlagDefinition.defineDefault(flags, "REDUNDANCY", () -> Optional.of(OptionalLong.of(1)), ORIGIN)).isEmpty();

        assertThat(flags.valueOf("REDUNDANCY")).hasValue(2);
    }

    @Test
    void aDefaultYieldsToAFlagTheProgramSet() {
        Flags flags = Flags.of(Map.of(), RESERVED);
        flags.define("SHELL", OptionalLong.empty(), "at lib.evo:4");

        assertThat(FlagDefinition.defineDefault(flags, "SHELL", () -> Optional.of(OptionalLong.of(1)), ORIGIN)).isEmpty();

        assertThat(flags.isSet("SHELL")).isTrue();
        assertThat(flags.valueOf("SHELL")).isEmpty();
    }

    @Test
    void aDefaultYieldsToAFlagTheConfigurationSwitchedOff() {
        Flags flags = Flags.of(Map.of("SHELL", FlagSetting.off()), RESERVED);

        assertThat(FlagDefinition.defineDefault(flags, "SHELL", () -> Optional.of(OptionalLong.empty()), ORIGIN)).isEmpty();

        assertThat(flags.isSet("SHELL")).isFalse();
        assertThat(flags.isOff("SHELL")).isTrue();
    }

    @Test
    void aDefaultThatYieldsDoesNotComputeItsValue() {
        Flags flags = Flags.of(Map.of("HALF", FlagSetting.set(3), "SHELL", FlagSetting.off()), RESERVED);

        assertThat(FlagDefinition.defineDefault(flags, "HALF", FlagDefinitionTest::notComputed, ORIGIN)).isEmpty();
        assertThat(FlagDefinition.defineDefault(flags, "SHELL", FlagDefinitionTest::notComputed, ORIGIN)).isEmpty();

        assertThat(flags.valueOf("HALF")).hasValue(3);
    }

    @Test
    void aDefaultWhoseValueCannotBeComputedSetsNothingAndReportsNothingItself() {
        Flags flags = Flags.of(Map.of(), RESERVED);

        assertThat(FlagDefinition.defineDefault(flags, "HALF", Optional::empty, ORIGIN)).isEmpty();

        assertThat(flags.isSet("HALF")).isFalse();
    }

    private static Optional<OptionalLong> notComputed() {
        throw new AssertionError("the value of a default that yields is not computed");
    }

    @Test
    void aDefaultAfterUndefTakesTheFlag() {
        Flags flags = Flags.of(Map.of("SHELL", FlagSetting.off()), RESERVED);
        flags.undefine("SHELL");

        assertThat(FlagDefinition.defineDefault(flags, "SHELL", () -> Optional.of(OptionalLong.empty()), ORIGIN)).isEmpty();

        assertThat(flags.isSet("SHELL")).isTrue();
    }

    @Test
    void aDefaultOfAReservedNameIsRejected() {
        Flags flags = Flags.of(Map.of(), RESERVED);

        assertThat(FlagDefinition.defineDefault(flags, "MIN", () -> Optional.of(OptionalLong.empty()), ORIGIN))
                .contains("'MIN' is a function and cannot be defined.");
    }

    @Test
    void aDefinitionOfAFlagThatIsSetMustRepeatIt() {
        Flags flags = Flags.of(Map.of("ROWS", FlagSetting.set(4)), RESERVED);

        assertThat(FlagDefinition.define(flags, "ROWS", OptionalLong.of(4), ORIGIN)).isEmpty();
        assertThat(FlagDefinition.define(flags, "ROWS", OptionalLong.of(5), ORIGIN)).contains(
                "Cannot define flag ROWS as 5: it is already defined as 4 by the configuration; .UNDEF it first to change it");
        assertThat(FlagDefinition.define(flags, "ROWS", OptionalLong.empty(), ORIGIN)).contains(
                "Cannot define flag ROWS without a value: it is already defined as 4 by the configuration;"
                        + " .UNDEF it first to change it");
    }

    @Test
    void aDefinitionOfAFlagThatIsSwitchedOffIsRejected() {
        Flags flags = Flags.of(Map.of("SHELL", FlagSetting.off()), RESERVED);

        assertThat(FlagDefinition.define(flags, "SHELL", OptionalLong.empty(), ORIGIN))
                .hasValueSatisfying(message -> assertThat(message).startsWith("Cannot define flag SHELL: the configuration switched it off"));
    }
}
