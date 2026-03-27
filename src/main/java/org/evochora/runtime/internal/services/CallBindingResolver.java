package org.evochora.runtime.internal.services;

import org.evochora.runtime.model.Organism;

import java.util.Map;

/**
 * Resolves parameter bindings for procedure calls by retrieving
 * pre-compiled bindings from the global registry.
 * All methods are static to avoid per-instruction object allocation on the hotpath.
 */
public final class CallBindingResolver {

    private CallBindingResolver() {}

    /**
     * Resolves the parameter bindings for the current CALL instruction.
     * <p>
     * The only permitted method is to retrieve the pre-compiled bindings from the
     * global registry. A fallback to parsing the source code at runtime is not
     * allowed as it undermines evolutionary stability.
     *
     * @param context The execution context containing the organism and the world.
     * @return Map from formal register ID to source register ID, or null if not found.
     */
    public static Map<Integer, Integer> resolveBindings(ExecutionContext context) {
        Organism organism = context.getOrganism();
        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        // The only correct method: Global Registry (absolute coordinate)
        CallBindingRegistry registry = CallBindingRegistry.getInstance();
        Map<Integer, Integer> bindings = registry.getBindingForAbsoluteCoord(ipBeforeFetch);
        if (bindings != null) {
            return bindings;
        }

        return null;
    }
}
