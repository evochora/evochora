package org.evochora.runtime.internal.services;

import org.evochora.runtime.model.Organism;

import java.util.Map;

/**
 * Decouples the strategies for resolving parameter bindings for procedure calls.
 * For example, it resolves `.WITH` clauses.
 */
public class CallBindingResolver {

    private final ExecutionContext context;

    /**
     * Creates a new resolver that operates on the given execution context.
     *
     * @param context The context containing the organism and the world.
     */
    public CallBindingResolver(ExecutionContext context) {
        this.context = context;
    }

    /**
     * Resolves the parameter bindings for the current CALL instruction.
     * <p>
     * The only permitted method is to retrieve the pre-compiled bindings from the
     * global registry. A fallback to parsing the source code at runtime is not
     * allowed as it undermines evolutionary stability.
     *
     * @return Map from formal register ID to source register ID, or null if not found.
     */
    public Map<Integer, Integer> resolveBindings() {
        Organism organism = context.getOrganism();
        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        // The only correct method: Global Registry (absolute coordinate)
        CallBindingRegistry registry = CallBindingRegistry.getInstance();
        Map<Integer, Integer> bindings = registry.getBindingForAbsoluteCoord(ipBeforeFetch);
        if (bindings != null) {
            return bindings;
        }

        // The old fallback path that parsed the artifact at runtime has been
        // removed because it violates the condition that runtime logic
        // must be independent of the artifact.

        return null;
    }
}