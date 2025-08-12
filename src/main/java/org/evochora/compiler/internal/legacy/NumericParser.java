package org.evochora.compiler.internal.legacy;

public final class NumericParser {

    private NumericParser() {}

    public static int parseInt(String token) {
        if (token == null) throw new NumberFormatException("null");
        String s = token.trim();
        boolean negative = false;
        if (s.startsWith("+")) {
            s = s.substring(1);
        } else if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1);
        }
        int radix = 10;
        if (s.startsWith("0b") || s.startsWith("0B")) {
            radix = 2;
            s = s.substring(2);
        } else if (s.startsWith("0x") || s.startsWith("0X")) {
            radix = 16;
            s = s.substring(2);
        } else if (s.startsWith("0o") || s.startsWith("0O")) {
            radix = 8;
            s = s.substring(2);
        }
        if (s.isEmpty()) throw new NumberFormatException("Empty numeric literal");
        int value = Integer.parseInt(s, radix);
        return negative ? -value : value;
    }
}
