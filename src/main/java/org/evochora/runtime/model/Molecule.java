// src/main/java/org/evochora/world/Molecule.java
package org.evochora.runtime.model;

import org.evochora.runtime.Config;

public record Molecule(int type, int value) {

    public int toInt() {
        // GEÄNDERT: Diese Logik verhindert, dass DATA:0 oder STRUCTURE:0 fälschlicherweise
        // als der Integer 0 (reserviert für CODE:0) gespeichert werden.
        if (this.value() == 0 && this.type() == Config.TYPE_CODE) {
            return 0;
        }
        // Ansonsten wird der Typ immer mit dem Wert kombiniert.
        return this.type() | (this.value() & Config.VALUE_MASK);
    }

    public int toScalarValue() {
        return this.value();
    }

    public boolean isEmpty() {
        return this.type() == Config.TYPE_CODE && this.value() == 0;
    }

    public static Molecule fromInt(int fullValue) {
        if (fullValue == 0) {
            return new Molecule(Config.TYPE_CODE, 0);
        }
        int type = fullValue & Config.TYPE_MASK;
        int rawValue = fullValue & Config.VALUE_MASK;
        if ((rawValue & (1 << (Config.VALUE_BITS - 1))) != 0) {
            rawValue |= ~((1 << Config.VALUE_BITS) - 1);
        }
        return new Molecule(type, rawValue);
    }

    // Ownership helpers: zero ownerId means unowned
    public int getOwnerFrom(Environment environment, int... coord) {
        return environment.getOwnerId(coord);
    }

    public void setOwnerIn(Environment environment, int ownerId, int... coord) {
        environment.setOwnerId(ownerId, coord);
    }

    public static int getOwner(Environment environment, int... coord) {
        return environment.getOwnerId(coord);
    }

    public static void setOwner(Environment environment, int ownerId, int... coord) {
        environment.setOwnerId(ownerId, coord);
    }

    @Override
    public String toString() {
        String typePrefix = switch (this.type()) {
            case Config.TYPE_CODE -> "CODE";
            case Config.TYPE_DATA -> "DATA";
            case Config.TYPE_ENERGY -> "ENERGY";
            case Config.TYPE_STRUCTURE -> "STRUCTURE";
            default -> "UNKNOWN";
        };
        return typePrefix + ":" + this.toScalarValue();
    }
}