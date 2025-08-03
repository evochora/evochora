// src/main/java/org/evochora/world/Symbol.java
package org.evochora.world;

import org.evochora.Config;

public record Symbol(int type, int value) {

    public static Symbol fromInt(int fullValue) {
        if (fullValue == 0) {
            return new Symbol(Config.TYPE_CODE, 0);
        }

        int type = fullValue & Config.TYPE_MASK;

        int rawValue = fullValue & Config.VALUE_MASK;
        if ((rawValue & 0x00800000) != 0) {
            rawValue |= 0xFF000000;
        }
        int value = rawValue;

        return new Symbol(type, value);
    }

    public int toInt() {
        if (this.type() == Config.TYPE_CODE && this.value() == 0) {
            return 0;
        }
        // DIESE ZEILE IST KRITISCH für das Problem mit negativen Zahlen
        // und dem Überschreiben des Typ-Feldes.
        return this.type() | (this.value() & Config.VALUE_MASK);
    }

    public boolean isEmpty() {
        return this.toInt() == 0;
    }
}