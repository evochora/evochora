package org.evochora.runtime.internal.services;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A central, VM-wide registry for managing parameter bindings for CALL instructions.
 * Each binding maps formal register IDs (FDR_BASE+i, FLR_BASE+i) to source register IDs.
 * <p>
 * This class is implemented as a thread-safe singleton.
 */
public final class CallBindingRegistry {

    private static final CallBindingRegistry INSTANCE = new CallBindingRegistry();

    private final Map<Integer, Map<Integer, Integer>> bindingsByLinearAddress = new ConcurrentHashMap<>();
    private final Map<List<Integer>, Map<Integer, Integer>> bindingsByAbsoluteCoord = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent instantiation.
     */
    private CallBindingRegistry() {
        // Private constructor to prevent instantiation.
    }

    /**
     * Returns the singleton instance of the registry.
     * @return The singleton instance.
     */
    public static CallBindingRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a parameter binding for a CALL instruction at a specific linear address.
     *
     * @param linearAddress The linear address of the CALL instruction.
     * @param bindings      Map from formal register ID to source register ID.
     */
    public void registerBindingForLinearAddress(int linearAddress, Map<Integer, Integer> bindings) {
        bindingsByLinearAddress.put(linearAddress, Collections.unmodifiableMap(bindings));
    }

    /**
     * Registers a parameter binding for a CALL instruction at a specific absolute coordinate.
     *
     * @param absoluteCoord The absolute world coordinate of the CALL instruction.
     * @param bindings      Map from formal register ID to source register ID.
     */
    public void registerBindingForAbsoluteCoord(int[] absoluteCoord, Map<Integer, Integer> bindings) {
        List<Integer> key = Arrays.stream(absoluteCoord).boxed().toList();
        bindingsByAbsoluteCoord.put(key, Collections.unmodifiableMap(bindings));
    }

    /**
     * Retrieves the parameter binding for a given linear address.
     *
     * @param linearAddress The linear address.
     * @return Map from formal register ID to source register ID, or null if no binding is found.
     */
    public Map<Integer, Integer> getBindingForLinearAddress(int linearAddress) {
        return bindingsByLinearAddress.get(linearAddress);
    }

    /**
     * Retrieves the parameter binding for a given absolute coordinate.
     *
     * @param absoluteCoord The absolute coordinate.
     * @return Map from formal register ID to source register ID, or null if no binding is found.
     */
    public Map<Integer, Integer> getBindingForAbsoluteCoord(int[] absoluteCoord) {
        List<Integer> key = Arrays.stream(absoluteCoord).boxed().toList();
        return bindingsByAbsoluteCoord.get(key);
    }

    /**
     * Resets the state of the registry.
     * This is crucial for ensuring clean and independent test runs.
     */
    public void clearAll() {
        bindingsByLinearAddress.clear();
        bindingsByAbsoluteCoord.clear();
    }
}