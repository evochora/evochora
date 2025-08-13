package org.evochora.runtime.isa;

import java.util.Collections;
import java.util.List;

/**
 * Beschreibt die erwartete Signatur einer Instruktion, d.h. die Anzahl
 * und die Typen ihrer Argumente.
 *
 * @param argumentTypes Eine unveränderliche Liste der erwarteten Argumenttypen.
 */
public record InstructionSignature(List<InstructionArgumentType> argumentTypes) { // GEÄNDERT

    /**
     * Erstellt eine Signatur und stellt sicher, dass die Liste unveränderlich ist.
     */
    public InstructionSignature(List<InstructionArgumentType> argumentTypes) { // GEÄNDERT
        this.argumentTypes = Collections.unmodifiableList(argumentTypes);
    }

    /**
     * Gibt die erwartete Anzahl an Argumenten zurück.
     * @return Die Anzahl der Argumente.
     */
    public int getArity() {
        return argumentTypes.size();
    }

    /**
     * Statische Helfermethode für Instruktionen ohne Argumente.
     */
    public static InstructionSignature noArgs() {
        return new InstructionSignature(List.of());
    }

    /**
     * Statische Helfermethode für Instruktionen mit einem Argument.
     */
    public static InstructionSignature of(InstructionArgumentType type1) { // GEÄNDERT
        return new InstructionSignature(List.of(type1));
    }

    /**
     * Statische Helfermethode für Instruktionen mit zwei Argumenten.
     */
    public static InstructionSignature of(InstructionArgumentType type1, InstructionArgumentType type2) { // GEÄNDERT
        return new InstructionSignature(List.of(type1, type2));
    }

    /**
     * Statische Helfermethode für Instruktionen mit drei Argumenten.
     */
    public static InstructionSignature of(InstructionArgumentType type1, InstructionArgumentType type2, InstructionArgumentType type3) { // GEÄNDERT
        return new InstructionSignature(List.of(type1, type2, type3));
    }
}