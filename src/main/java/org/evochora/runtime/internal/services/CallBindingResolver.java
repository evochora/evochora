package org.evochora.runtime.internal.services;

import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;

/**
 * Entkoppelt die Strategien zur Auflösung von Parameterbindungen für Prozeduraufrufe.
 * Löst z.B. `.WITH`-Klauseln auf.
 */
public class CallBindingResolver {

    private final ExecutionContext context;

    /**
     * Erstellt einen neuen Resolver, der auf dem gegebenen Ausführungskontext operiert.
     *
     * @param context Der Kontext, der den Organismus und die Welt enthält.
     */
    public CallBindingResolver(ExecutionContext context) {
        this.context = context;
    }

    /**
     * Löst die Parameterbindungen für die aktuelle CALL-Instruktion auf.
     * <p>
     * Diese Methode versucht, die Bindungen in einer bestimmten Reihenfolge zu finden:
     * 1. Über die lineare Adresse der Instruktion in der {@link CallBindingRegistry}.
     * 2. Als Fallback über die absolute Weltkoordinate in der {@link CallBindingRegistry}.
     *
     * @return Ein Array von Register-IDs, die gebunden sind, oder {@code null}, wenn keine Bindung gefunden wurde.
     */
    public int[] resolveBindings() {
        Organism organism = context.getOrganism();
        // World world = context.getWorld(); // Nicht mehr benötigt

        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        // TODO: [Phase 1 Workaround] Aktuell wird eine globale Registry basierend auf absoluten Koordinaten
        //       verwendet. Dies ist eine temporäre "Krücke" und führt zu Fehlern bei dynamisch
        //       erstelltem Code (z.B. durch FORK), da die Metadaten nicht mitkopiert werden.
        //
        // TODO: [Phase 2 Fix] Dies wird durch die Einführung eines `ProgramArtifact` im `ExecutionContext`
        //       behoben. Der Resolver wird dann die Bindungen aus den Metadaten des Artefakts lesen,
        //       basierend auf der *relativen* Adresse der Instruktion innerhalb des Programms.

        CallBindingRegistry registry = CallBindingRegistry.getInstance();
        // Wir verlassen uns vorübergehend nur auf den Fallback über die absolute Koordinate.
        int[] bindings = registry.getBindingForAbsoluteCoord(ipBeforeFetch);

        return bindings;
    }
}