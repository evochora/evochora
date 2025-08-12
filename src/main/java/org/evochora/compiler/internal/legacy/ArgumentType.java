// src/main/java/org/evochora/assembler/ArgumentType.java
package org.evochora.compiler.internal.legacy;

/**
 * Repräsentiert den Typ eines Arguments für das spezialisierte Logging.
 * Ersetzt die Notwendigkeit für Booleans wie 'isLabel'.
 */
public enum ArgumentType {
    REGISTER,       // Das Argument ist eine Registernummer (z.B. %DR0)
    LABEL,          // Das Argument ist ein Label-Verweis (z.B. MY_LABEL)
    LITERAL,        // Das Argument ist ein direkter Integer-Wert (z.B. 123)
    COORDINATE      // Das Argument ist ein n-dimensionaler Vektor (z.B. [1|0])
}