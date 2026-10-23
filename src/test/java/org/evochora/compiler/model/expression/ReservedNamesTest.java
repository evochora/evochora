package org.evochora.compiler.model.expression;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reserved names compare without regard to letter case, in any default locale, and word the
 * message that rejects a definition by what the name is and the role it would take.
 */
@Tag("unit")
class ReservedNamesTest {

    private final ReservedNames names = ReservedNames.of(Map.of("POSITION", "a function"), Set.of("@"));

    @Test
    void aNameThatIsSomethingIsRejectedWithWhatItIs() {
        assertThat(names.rejection("Position", "a macro name"))
                .contains("'Position' is a function and cannot be a macro name.");
    }

    @Test
    void aNameReservedAsItIsIsRejectedWithoutWhatItIs() {
        assertThat(names.rejection("@", "defined")).contains("'@' cannot be defined.");
    }

    @Test
    void anOtherNameIsNotRejected() {
        assertThat(names.rejection("SPOT", "defined")).isEmpty();
    }

    @Test
    void aNameWithAnIIsReservedInATurkishDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(ReservedNames.of(Map.of("position", "a function"), Set.of()).rejection("position", "defined"))
                    .contains("'position' is a function and cannot be defined.");
        } finally {
            Locale.setDefault(before);
        }
    }
}
