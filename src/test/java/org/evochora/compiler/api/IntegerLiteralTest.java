package org.evochora.compiler.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IntegerLiteral}: the forms a program can write an integer in, and the
 * texts that are none.
 */
@Tag("unit")
class IntegerLiteralTest {

    @Test
    void readsDecimalHexadecimalAndBinaryWithAnOptionalMinus() {
        assertThat(IntegerLiteral.parse("12")).hasValue(12);
        assertThat(IntegerLiteral.parse("0")).hasValue(0);
        assertThat(IntegerLiteral.parse("-3")).hasValue(-3);
        assertThat(IntegerLiteral.parse("0x1F")).hasValue(31);
        assertThat(IntegerLiteral.parse("0XfF")).hasValue(255);
        assertThat(IntegerLiteral.parse("-0x10")).hasValue(-16);
        assertThat(IntegerLiteral.parse("0b101")).hasValue(5);
        assertThat(IntegerLiteral.parse("-0B11")).hasValue(-3);
        assertThat(IntegerLiteral.parse("2147483647")).hasValue(Integer.MAX_VALUE);
    }

    @Test
    void rejectsEveryOtherForm() {
        for (String text : new String[]{"", "-", "+3", "--3", "0x", "0b", "0o17", "0b2", "0xG", "1.5", "12a", " 1",
                "2147483648", "0x80000000"}) {
            assertThat(IntegerLiteral.parse(text)).describedAs(text).isEqualTo(OptionalInt.empty());
        }
    }
}
