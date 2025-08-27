package org.evochora.runtime.internal.services;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A central, VM-wide registry for managing parameter bindings for CALL instructions.
 * <p>
 * This class is implemented as a thread-safe singleton.
 */
public final class CallBindingRegistry {

    private static final CallBindingRegistry INSTANCE = new CallBindingRegistry();

    private final Map<Integer, int[]> bindingsByLinearAddress = new ConcurrentHashMap<>();
    private final Map<List<Integer>, int[]> bindingsByAbsoluteCoord = new ConcurrentHashMap<>();

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
     * @param drIds         An array of register IDs to be bound.
     */
    public void registerBindingForLinearAddress(int linearAddress, int[] drIds) {
        bindingsByLinearAddress.put(linearAddress, Arrays.copyOf(drIds, drIds.length));
    }

    /**
     * Registers a parameter binding for a CALL instruction at a specific absolute coordinate.
     *
     * @param absoluteCoord The absolute world coordinate of the CALL instruction.
     * @param drIds         An array of register IDs to be bound.
     */
    public void registerBindingForAbsoluteCoord(int[] absoluteCoord, int[] drIds) {
        List<Integer> key = Arrays.stream(absoluteCoord).boxed().toList();
        bindingsByAbsoluteCoord.put(key, Arrays.copyOf(drIds, drIds.length));
    }

    /**
     * Retrieves the parameter binding for a given linear address.
     *
     * @param linearAddress The linear address.
     * @return The array of bound register IDs, or null if no binding is found.
     */
    public int[] getBindingForLinearAddress(int linearAddress) {
        return bindingsByLinearAddress.get(linearAddress);
    }

    /**
     * Retrieves the parameter binding for a given absolute coordinate.
     *
     * @param absoluteCoord The absolute coordinate.
     * @return The array of bound register IDs, or null if no binding is found.
     */
    public int[] getBindingForAbsoluteCoord(int[] absoluteCoord) {
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