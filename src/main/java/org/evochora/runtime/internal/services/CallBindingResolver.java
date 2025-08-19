package org.evochora.runtime.internal.services;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
     * Die einzige zulässige Methode ist, die vorkompilierten Bindungen aus der
     * globalen Registry abzurufen. Ein Fallback auf das Parsen des Quellcodes
     * zur Laufzeit ist nicht erlaubt, da dies die evolutionäre Stabilität untergräbt.
     *
     * @return Array der gebundenen Register-IDs oder null.
     */
    public int[] resolveBindings() {
        Organism organism = context.getOrganism();
        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        // Die einzige korrekte Methode: Globale Registry (absolute Koordinate)
        CallBindingRegistry registry = CallBindingRegistry.getInstance();
        int[] bindings = registry.getBindingForAbsoluteCoord(ipBeforeFetch);
        if (bindings != null) {
            return bindings;
        }

        // Der alte Fallback-Pfad, der das Artefakt zur Laufzeit parst, wurde
        // entfernt, da er die Bedingung verletzt, dass die Laufzeitlogik
        // unabhängig vom Artefakt sein muss.

        return null;
    }
}