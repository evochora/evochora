package org.evochora.runtime.model;

import org.evochora.runtime.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry for molecule type definitions.
 * <p>
 * Provides bidirectional conversion between int types (from {@link Config}) and string names,
 * and a stable order over the registered types. This eliminates hardcoding of molecule type
 * strings throughout the codebase and gives every consumer that needs a per-type table
 * (colour palettes, count arrays, column lists) one source for its size and its indices.
 * <p>
 * Key features:
 * <ul>
 *   <li>Single source of truth for molecule type name mappings</li>
 *   <li>Registration order defines a dense index range 0 .. {@link #typeCount()} - 1</li>
 *   <li>Tolerant conversion: typeToName() returns "UNKNOWN" for unrecognized types</li>
 *   <li>Strict conversion: nameToType() throws IllegalArgumentException for unrecognized names</li>
 *   <li>Case-insensitive string input for nameToType()</li>
 * </ul>
 * <p>
 * Thread Safety: This class is thread-safe after static initialization completes.
 * All methods are read-only after initialization.
 * <p>
 * To add a new molecule type:
 * <ol>
 *   <li>Add constant to {@link Config} (e.g., {@code TYPE_FOOD})</li>
 *   <li>Register here during static initialization: {@code register(Config.TYPE_FOOD, "FOOD")}</li>
 *   <li>All other code will automatically use the registry</li>
 * </ol>
 * A constant that exists in {@link Config} without a registration line here fails the unit test
 * that walks the {@code TYPE_*} fields by reflection, so the omission is caught by the build.
 */
public final class MoleculeTypeRegistry {
    
    /**
     * Maps molecule type integers to their string names.
     */
    private static final Map<Integer, String> TYPE_TO_NAME = new HashMap<>();
    
    /**
     * Maps molecule type string names (uppercase) to their integer values.
     */
    private static final Map<String, Integer> NAME_TO_TYPE = new HashMap<>();

    /**
     * The registered molecule types in registration order.
     */
    private static final List<Integer> ORDERED_TYPES = new ArrayList<>();

    /**
     * Maps each registered molecule type to its position in {@link #ORDERED_TYPES}.
     */
    private static final Map<Integer, Integer> TYPE_TO_INDEX = new HashMap<>();
    
    static {
        // Register all molecule types from Config
        register(Config.TYPE_CODE, "CODE");
        register(Config.TYPE_DATA, "DATA");
        register(Config.TYPE_ENERGY, "ENERGY");
        register(Config.TYPE_STRUCTURE, "STRUCTURE");
        register(Config.TYPE_LABEL, "LABEL");
        register(Config.TYPE_LABELREF, "LABELREF");
        register(Config.TYPE_REGISTER, "REGISTER");
        register(Config.TYPE_STATE, "STATE");
    }
    
    /**
     * Private constructor to prevent instantiation.
     */
    private MoleculeTypeRegistry() {
        throw new AssertionError("Utility class - cannot be instantiated");
    }
    
    /**
     * Registers a molecule type mapping and appends it to the registration order.
     * <p>
     * This method is package-private to allow registration only from within this package.
     * New types should be registered during static initialization.
     *
     * @param type The molecule type integer value from Config
     * @param name The human-readable name for this type (will be stored as uppercase)
     * @throws IllegalArgumentException if the name is null or blank
     * @throws IllegalStateException if the type or name is already registered
     */
    static void register(int type, String name) {
        if (TYPE_TO_NAME.containsKey(type)) {
            throw new IllegalStateException("Molecule type " + type + " is already registered");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Molecule type name cannot be null or empty");
        }
        String upperName = name.toUpperCase();
        if (NAME_TO_TYPE.containsKey(upperName)) {
            throw new IllegalStateException("Molecule type name '" + name + "' is already registered");
        }
        TYPE_TO_NAME.put(type, upperName);
        NAME_TO_TYPE.put(upperName, type);
        TYPE_TO_INDEX.put(type, ORDERED_TYPES.size());
        ORDERED_TYPES.add(type);
    }
    
    /**
     * Converts a molecule type integer to its string name.
     * <p>
     * This is a tolerant conversion: unknown types return "UNKNOWN" rather than throwing an exception.
     * This allows the runtime to handle legacy data or corrupted molecules gracefully.
     *
     * @param type The molecule type integer value
     * @return The string name for this type (e.g., "CODE", "DATA"), or "UNKNOWN" if not registered
     */
    public static String typeToName(int type) {
        return TYPE_TO_NAME.getOrDefault(type, "UNKNOWN");
    }
    
    /**
     * Converts a molecule type string name to its integer value.
     * <p>
     * This is a strict conversion: unknown names throw an exception. This is intended for use
     * in the compiler where invalid types should be caught early.
     * <p>
     * Input is case-insensitive: "code", "CODE", and "Code" all return the same type.
     *
     * @param name The molecule type name (case-insensitive)
     * @return The integer type value from Config
     * @throws IllegalArgumentException if the name is null, empty, or not registered
     */
    public static int nameToType(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Molecule type name cannot be null or empty");
        }
        Integer type = NAME_TO_TYPE.get(name.toUpperCase());
        if (type == null) {
            throw new IllegalArgumentException("Unknown molecule type: '" + name + 
                "'. Valid types: " + String.join(", ", NAME_TO_TYPE.keySet()));
        }
        return type;
    }
    
    /**
     * Checks if a molecule type integer is registered.
     *
     * @param type The molecule type integer value
     * @return true if the type is registered, false otherwise
     */
    public static boolean isRegistered(int type) {
        return TYPE_TO_NAME.containsKey(type);
    }
    
    /**
     * Returns an unmodifiable view of all registered molecule types.
     * <p>
     * This is useful for serializing the complete type mapping to clients
     * (e.g., in metadata responses for the visualizer API).
     *
     * @return Unmodifiable map of type ID to type name (e.g., {0: "CODE", 1: "DATA", ...})
     */
    public static Map<Integer, String> getAllTypes() {
        return Collections.unmodifiableMap(TYPE_TO_NAME);
    }

    /**
     * Returns the number of registered molecule types.
     * <p>
     * This is the size a per-type table needs in order to cover every type, and the exclusive
     * upper bound of the indices returned by {@link #indexOf(int)}.
     *
     * @return The count of registered types, always at least one
     */
    public static int typeCount() {
        return ORDERED_TYPES.size();
    }

    /**
     * Returns the position of a molecule type in the registration order.
     * <p>
     * The index is dense and stable for the lifetime of the process: the first registered type
     * has index 0, the last has index {@link #typeCount()} - 1. It is the index a per-type table
     * uses to address the entry belonging to this type.
     *
     * @param type The molecule type integer value
     * @return The zero-based index in registration order, or -1 if the type is not registered
     */
    public static int indexOf(int type) {
        return TYPE_TO_INDEX.getOrDefault(type, -1);
    }

    /**
     * Returns the registered molecule types in registration order.
     * <p>
     * The element at position {@code i} is the type whose {@link #indexOf(int)} is {@code i},
     * so iterating this list fills a per-type table in index order.
     *
     * @return Unmodifiable list of the registered type integer values, in registration order
     */
    public static List<Integer> orderedTypes() {
        return Collections.unmodifiableList(ORDERED_TYPES);
    }
}
