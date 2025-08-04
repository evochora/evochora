// src/main/java/org/evochora/organism/Action.java
package org.evochora.organism;

import org.evochora.Simulation;

public abstract class Action {
    protected final Organism organism;

    // NEU: Status der Ausführung innerhalb des Ticks
    protected boolean executedInTick = false;

    // NEU: Enum für Konfliktlösungs-Status
    public enum ConflictResolutionStatus {
        NOT_APPLICABLE,         // Nicht weltverändernd oder nicht am Konflikt beteiligt
        WON_EXECUTION,          // Hat den Konflikt gewonnen und wird ausgeführt
        LOST_TARGET_OCCUPIED,   // Verloren, weil Zielzelle belegt war (z.B. POKE auf belegte Zelle)
        LOST_TARGET_EMPTY,      // Verloren, weil Zielzelle leer war (z.B. PEEK, wenn es etwas entfernen wollte, was nicht da war)
        LOST_LOWER_ID_WON,      // Verloren, weil eine Aktion mit niedrigerer Organismus-ID gewonnen hat
        LOST_OTHER_REASON       // Anderer, unspezifischer Grund für den Verlust
    }

    // NEU: Konfliktstatus dieser Aktion
    protected ConflictResolutionStatus conflictStatus = ConflictResolutionStatus.NOT_APPLICABLE;

    public Action(Organism organism) {
        this.organism = organism;
    }

    public final Organism getOrganism() {
        return this.organism;
    }

    public abstract void execute(Simulation simulation);

    // NEU: Getter und Setter für executedInTick
    public boolean isExecutedInTick() {
        return executedInTick;
    }

    public void setExecutedInTick(boolean executedInTick) {
        this.executedInTick = executedInTick;
    }

    // NEU: Getter und Setter für conflictStatus
    public ConflictResolutionStatus getConflictStatus() {
        return conflictStatus;
    }

    public void setConflictStatus(ConflictResolutionStatus conflictStatus) {
        this.conflictStatus = conflictStatus;
    }

    // Konvention für statische Methoden, die jede Unterklasse implementieren muss.
    // public static Action plan(Organism organism, World world)
    // public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap)
}