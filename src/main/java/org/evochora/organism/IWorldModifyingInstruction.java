// src/main/java/org/evochora/organism/IWorldModifyingInstruction.java
package org.evochora.organism;

import java.util.List;

/**
 * Interface für Instruktionen, die direkt Zellen in der Welt verändern
 * und daher an der Konfliktlösung beteiligt sein müssen.
 */
public interface IWorldModifyingInstruction { // GEÄNDERT: Von IWorldModifyingAction umbenannt

    /**
     * Gibt eine Liste der n-dimensionalen Koordinaten zurück, die diese Instruktion
     * zu verändern versucht. Dies ist entscheidend für die Konfliktlösung.
     * @return Eine Liste von int[] Arrays, wobei jedes Array eine Koordinate darstellt.
     */
    List<int[]> getTargetCoordinates();

    /**
     * Gibt den Organismus zurück, der diese Instruktion ausführt.
     * Implementierung kommt aus der Instruction-Basisklasse, die dieses Interface implementiert.
     */
    Organism getOrganism();

    /**
     * Prüft, ob diese Instruktion in diesem Tick ausgeführt wurde.
     * Implementierung kommt aus der Instruction-Basisklasse.
     * @return true, wenn die Instruktion ausgeführt wurde, sonst false.
     */
    boolean isExecutedInTick();

    /**
     * Setzt den Ausführungsstatus dieser Instruktion für den aktuellen Tick.
     * Implementierung kommt aus der Instruction-Basisklasse.
     * @param executedInTick true, wenn die Instruktion ausgeführt wurde, sonst false.
     */
    void setExecutedInTick(boolean executedInTick);

    /**
     * Gibt den Konfliktlösungsstatus dieser Instruktion zurück.
     * Implementierung kommt aus der Instruction-Basisklasse.
     * @return Der ConflictResolutionStatus.
     */
    Instruction.ConflictResolutionStatus getConflictStatus();

    /**
     * Setzt den Konfliktlösungsstatus dieser Instruktion.
     * Implementierung kommt aus der Instruction-Basisklasse.
     * @param conflictStatus Der festgestellte ConflictResolutionStatus.
     */
    void setConflictStatus(Instruction.ConflictResolutionStatus conflictStatus);
}