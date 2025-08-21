package org.evochora.runtime.worldgen;

import org.evochora.runtime.model.Environment;

/**
 * Eine Schnittstelle für verschiedene Strategien, um Energie in der Welt zu verteilen.
 */
public interface IEnergyDistributionStrategy {
    /**
     * Diese Methode wird von der Simulation in jedem Tick aufgerufen, um potenziell
     * neue Energie in die Umgebung einzubringen.
     *
     * @param environment Die Weltumgebung, die modifiziert werden soll.
     * @param currentTick Die aktuelle Tick-Nummer der Simulation.
     */
    void distributeEnergy(Environment environment, long currentTick);
}