// src/main/java/org/evochora/world/Symbol.java
package org.evochora.world;

import org.evochora.Config;

public record Symbol(int type, int value) {

    /**
     * Erstellt ein Symbol-Objekt aus einem vollen 32-Bit-Integer-Wert.
     */
    public static Symbol fromInt(int fullValue) {
        // FINALE LOGIK: 0 ist immer Leerer Raum/NOP und hat den Typ CODE.
        if (fullValue == 0) {
            return new Symbol(Config.TYPE_CODE, 0);
        }
        int type = fullValue & Config.TYPE_MASK;
        int value = fullValue & Config.VALUE_MASK;
        return new Symbol(type, value);
    }

    /**
     * Konvertiert dieses Symbol-Objekt zurück in einen 32-Bit-Integer.
     */
    public int toInt() {
        // FINALE LOGIK: Der NOP-Befehl (Code, Wert 0) wird korrekt zu 0.
        if (this.type == Config.TYPE_CODE && this.value == 0) {
            return 0;
        }
        return this.type | this.value;
    }

    /**
     * Eine Zelle ist leer, wenn ihr Gesamtwert 0 ist.
     */
    public boolean isEmpty() {
        return this.toInt() == 0;
    }
}