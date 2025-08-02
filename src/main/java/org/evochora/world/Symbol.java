// src/main/java/org/evochora/world/Symbol.java
package org.evochora.world;

import org.evochora.Config;

public record Symbol(int type, int value) {

    public static Symbol fromInt(int fullValue) {
        if (fullValue == 0) {
            // 0 ist immer Leerer Raum/NOP und hat den Typ CODE.
            return new Symbol(Config.TYPE_CODE, 0);
        }
        int type = fullValue & Config.TYPE_MASK;
        int value = fullValue & Config.VALUE_MASK;
        return new Symbol(type, value);
    }

    public int toInt() {
        // KORRIGIERT: Nur der NOP-Befehl (Code, Wert 0) wird zum Gesamtwert 0.
        // Ein Daten-Symbol mit Wert 0 (z.B. TYPE_DATA | 0) behält seinen Typ-Header.
        if (this.type() == Config.TYPE_CODE && this.value() == 0) {
            return 0;
        }
        return this.type() | this.value();
    }

    public boolean isEmpty() {
        // Eine Zelle ist leer, wenn ihr Gesamtwert 0 ist.
        return this.toInt() == 0;
    }
}