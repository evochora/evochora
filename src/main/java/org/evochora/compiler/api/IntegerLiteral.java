package org.evochora.compiler.api;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an integer in the forms a program can write one: decimal digits, or {@code 0x} or
 * {@code 0b} in either case followed by hexadecimal or binary digits, each with an optional
 * leading minus. The digits after the sign must fit an {@code int}; the minus negates them.
 */
public final class IntegerLiteral {

    private static final Pattern FORM = Pattern.compile("(-?)(0[xX]|0[bB])?([0-9A-Za-z]+)");

    private IntegerLiteral() {
    }

    /**
     * Reads an integer literal.
     *
     * @param text The literal, without surrounding whitespace.
     * @return The integer, or empty if the text is no such literal or its digits do not fit an
     *         {@code int}.
     */
    public static OptionalInt parse(String text) {
        Matcher form = FORM.matcher(text);
        if (!form.matches()) {
            return OptionalInt.empty();
        }
        String prefix = form.group(2);
        int radix = prefix == null ? 10 : Character.toLowerCase(prefix.charAt(1)) == 'x' ? 16 : 2;
        try {
            int value = Integer.parseInt(form.group(3), radix);
            return OptionalInt.of(form.group(1).isEmpty() ? value : -value);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
