package org.evochora.runtime;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.Agent;
import org.evochora.runtime.model.Environment;

/**
 * TODO: [Phase 3] Dies ist der zukünftige Kern der Ausführungsumgebung.
 *  Diese Klasse wird für die Orchestrierung der Ausführung von Agenten-Code
 *  innerhalb einer Umgebung verantwortlich sein. Sie wird die Logik kapseln,
 *  die derzeit in der {@code Simulation.tick()}-Methode verstreut ist.
 *  <p>
 *  In ihrer finalen Form wird sie einen Agenten und sein zugehöriges
 *  Programmartefakt nehmen und einen einzelnen Tick oder eine bestimmte Anzahl
 *  von Instruktionen ausführen.
 */
public class VirtualMachine {

    private final Environment environment;

    /**
     * Erstellt eine neue VM, die an eine bestimmte Umgebung gebunden ist.
     *
     * @param environment Die Umgebung, in der die Ausführung stattfindet.
     */
    public VirtualMachine(Environment environment) {
        this.environment = environment;
    }

    /**
     * Führt einen einzelnen Ausführungs-Tick für den gegebenen Agenten durch.
     * <p>
     * TODO: Die Implementierung wird die Logik aus Simulation.tick() übernehmen:
     * 1. Instruktion am IP des Agenten planen (planTick).
     * 2. Kosten für die Instruktion abziehen.
     * 3. Instruktion ausführen (instruction.execute).
     * 4. IP des Agenten vorrücken.
     *
     * @param agent Der Agent, dessen Code ausgeführt werden soll.
     * @param artifact Das kompilierte Programmartefakt, das zum Agenten gehört.
     */
    public void executeTick(Agent agent, ProgramArtifact artifact) {
        // Diese Methode wird in den nächsten Schritten implementiert.
        // Vorerst bleibt sie leer.
    }
}
