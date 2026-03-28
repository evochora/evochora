package org.evochora.runtime.internal.services;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A central, VM-wide registry for managing parameter bindings for CALL instructions.
 * Each binding maps formal register IDs (FDR_BASE+i, FLR_BASE+i) to source register IDs.
 * <p>
 * Bindings are keyed by flat index (computed from absolute coordinates via
 * {@link org.evochora.runtime.model.EnvironmentProperties#toFlatIndex(int[])}).
 * This avoids boxing and allocation on the hotpath.
 * <p>
 * This class is implemented as a thread-safe singleton.
 */
public final class CallBindingRegistry {

    private static final CallBindingRegistry INSTANCE = new CallBindingRegistry();

    private final Map<Integer, Map<Integer, Integer>> bindingsByFlatIndex = new ConcurrentHashMap<>();

    private CallBindingRegistry() {}

    /**
     * Returns the singleton instance of the registry.
     * @return The singleton instance.
     */
    public static CallBindingRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a parameter binding for a CALL instruction at a specific flat index.
     *
     * @param flatIndex The flat index of the CALL instruction's absolute coordinate.
     * @param bindings  Map from formal register ID to source register ID.
     */
    public void registerBinding(int flatIndex, Map<Integer, Integer> bindings) {
        bindingsByFlatIndex.put(flatIndex, Map.copyOf(bindings));
    }

    /**
     * Retrieves the parameter binding for a given flat index.
     *
     * @param flatIndex The flat index of the absolute coordinate.
     * @return Map from formal register ID to source register ID, or null if no binding is found.
     */
    public Map<Integer, Integer> getBinding(int flatIndex) {
        return bindingsByFlatIndex.get(flatIndex);
    }

    /**
     * Resets the state of the registry.
     * This is crucial for ensuring clean and independent test runs.
     */
    public void clearAll() {
        bindingsByFlatIndex.clear();
    }
}
