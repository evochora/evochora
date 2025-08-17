package org.evochora.runtime.internal.services;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;

import java.util.ArrayList;
import java.util.List;

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
     * Reihenfolge:
     * 1) Artefakt-gestützt: relative Adresse -> SourceMap -> Zeile parsen (Tokens nach WITH)
     * 2) Fallback: CallBindingRegistry (absolute Koordinate)
     *
     * @return Array der gebundenen Register-IDs oder null.
     */
    public int[] resolveBindings() {
        Organism organism = context.getOrganism();
        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        // 1) Artefakt-gestützt über SourceMap
        try {
            ProgramArtifact artifact = context.getArtifact();
            if (artifact != null && artifact.relativeCoordToLinearAddress() != null && artifact.sourceMap() != null) {
                int[] origin = organism.getInitialPosition();
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < ipBeforeFetch.length; i++) {
                    if (i > 0) key.append('|');
                    key.append(ipBeforeFetch[i] - origin[i]);
                }
                Integer addr = artifact.relativeCoordToLinearAddress().get(key.toString());
                if (addr != null && artifact.sourceMap().get(addr) != null) {
                    String line = artifact.sourceMap().get(addr).lineContent();
                    if (line != null) {
                        String upper = line.toUpperCase();
                        int withIdx = upper.indexOf(" WITH ");
                        if (withIdx >= 0) {
                            String afterWith = line.substring(withIdx + 6).trim();
                            String[] parts = afterWith.split("\\s+");
                            List<Integer> regs = new ArrayList<>();
                            for (String p : parts) {
                                Integer regId = Instruction.resolveRegToken(p, java.util.Map.of());
                                if (regId != null) regs.add(regId);
                            }
                            if (!regs.isEmpty()) {
                                return regs.stream().mapToInt(Integer::intValue).toArray();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) { }

        // 2) Fallback: Globale Registry (absolute Koordinate)
        CallBindingRegistry registry = CallBindingRegistry.getInstance();
        return registry.getBindingForAbsoluteCoord(ipBeforeFetch);
    }
}