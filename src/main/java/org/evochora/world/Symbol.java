// src/main/java/org/evochora/world/Symbol.java
package org.evochora.world;

import org.evochora.Config;

public record Symbol(int type, int value) {

    public static Symbol fromInt(int fullValue) {
        // KORRIGIERT: Prüft direkt auf den Wert 0 für "Leer".
        if (fullValue == 0) {
            // Wir geben einen neutralen Typ zurück, da 0 keinen Typ-Header hat.
            // TYPE_DATA ist hier ein sicherer Platzhalter.
            return new Symbol(Config.TYPE_DATA, 0);
        }
        int type = fullValue & Config.TYPE_MASK;
        int value = fullValue & Config.VALUE_MASK;
        return new Symbol(type, value);
    }

    public int toInt() {
        // KORRIGIERT: Prüft auf den Wert 0. Wenn der Wert 0 ist, ist die Zelle leer.
        if (this.value == 0) {
            return 0;
        }
        return this.type | this.value;
    }

    public boolean isEmpty() {
        // Eine Zelle ist leer, wenn ihr Gesamtwert 0 ist.
        return this.toInt() == 0;
    }
}