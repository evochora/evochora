// src/main/java/org/evochora/organism/IWorldModifyingAction.java
package org.evochora.organism;

import java.util.List;

/**
 * Interface für Aktionen, die direkt Zellen in der Welt verändern
 * und daher an der Konfliktlösung beteiligt sein müssen.
 */
public interface IWorldModifyingAction {

    /**
     * Gibt eine Liste der n-dimensionalen Koordinaten zurück, die diese Aktion
     * zu verändern versucht. Dies ist entscheidend für die Konfliktlösung.
     * @return Eine Liste von int[] Arrays, wobei jedes Array eine Koordinate darstellt.
     */
    List<int[]> getTargetCoordinates();

    /**
     * Gibt den Organismus zurück, der diese Aktion ausführt.
     * Implementierung kommt aus der Action-Basisklasse.
     */
    Organism getOrganism();

    // NEU: Deklariert die Methoden für den Ausführungs- und Konfliktstatus
    // Die Implementierung kommt aus der Action-Basisklasse.

    /**
     * Prüft, ob diese Aktion in diesem Tick ausgeführt wurde.
     * @return true, wenn die Aktion ausgeführt wurde, sonst false.
     */
    boolean isExecutedInTick();

    /**
     * Setzt den Ausführungsstatus dieser Aktion für den aktuellen Tick.
     * @param executedInTick true, wenn die Aktion ausgeführt wurde, sonst false.
     */
    void setExecutedInTick(boolean executedInTick);

    /**
     * Gibt den Konfliktlösungsstatus dieser Aktion zurück.
     * @return Der ConflictResolutionStatus.
     */
    Action.ConflictResolutionStatus getConflictStatus();

    /**
     * Setzt den Konfliktlösungsstatus dieser Aktion.
     * @param conflictStatus Der festgestellte ConflictResolutionStatus.
     */
    void setConflictStatus(Action.ConflictResolutionStatus conflictStatus);
}