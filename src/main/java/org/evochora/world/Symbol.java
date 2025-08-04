// src/main/java/org/evochora/world/Symbol.java
package org.evochora.world;

import org.evochora.Config;

public record Symbol(int type, int value) {

    public static Symbol fromInt(int fullValue) {
        if (fullValue == 0) {
            // 0 ist immer Leerer Raum/NOP und hat den Typ CODE.
            // Der Wert 0 wird hier explizit gesetzt, um das Verhalten beizubehalten.
            return new Symbol(Config.TYPE_CODE, 0);
        }

        // Typ sind die Bits, die durch TYPE_MASK definiert sind
        int type = fullValue & Config.TYPE_MASK;

        // Wert sind die Bits, die durch VALUE_MASK definiert sind
        int rawValue = fullValue & Config.VALUE_MASK;

        // KORRIGIERT: Sign-Extension für den Wert-Teil.
        // Wenn das höchste Bit des Wert-Bereichs (VALUE_BITS - 1) gesetzt ist,
        // dann ist es eine negative Zahl. Fülle die Bits oberhalb des Wert-Bereichs
        // mit 1en auf, um einen korrekten 32-Bit negativen Integer zu erhalten.
        // Dies geschieht durch XOR mit der Maske für die Auffüllung.
        if ((rawValue & (1 << (Config.VALUE_BITS - 1))) != 0) {
            rawValue |= ~((1 << Config.VALUE_BITS) - 1); // Füllt mit 1en oberhalb der VALUE_BITS auf
        }

        return new Symbol(type, rawValue);
    }

    public int toInt() {
        // KORRIGIERT: Nur der NOP-Befehl (Code, Wert 0) wird zum Gesamtwert 0.
        // Ein Daten-Symbol mit Wert 0 (z.B. TYPE_DATA | 0) behält seinen Typ-Header.
        if (this.type() == Config.TYPE_CODE && this.value() == 0) {
            return 0;
        }
        // KORRIGIERT: Der Wert muss vor dem ORen mit dem Typ-Header maskiert werden,
        // um sicherzustellen, dass keine Bits des Wertes in den Typ-Bereich ragen.
        // Dies war der Kern des vorherigen Problems mit -1.
        int maskedValue = this.value() & Config.VALUE_MASK;
        return this.type() | maskedValue;
    }

    public boolean isEmpty() {
        // Eine Zelle ist leer, wenn ihr Gesamtwert 0 ist.
        // Dies bedeutet, dass sie ein NOP (TYPE_CODE | 0) ist.
        return this.toInt() == 0;
    }
}