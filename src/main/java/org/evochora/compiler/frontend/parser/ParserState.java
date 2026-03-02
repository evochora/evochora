package org.evochora.compiler.frontend.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A generic type-safe container for parser state that features can use
 * to store and retrieve their own state objects during parsing.
 * Each feature defines its own state class and uses it as the key.
 */
public class ParserState {

    private final Map<Class<?>, Object> state = new HashMap<>();

    /**
     * Retrieves the state object associated with the given key type.
     * @param key The class used as the key.
     * @param <T> The type of the state object.
     * @return The state object, or {@code null} if no state is associated with the key.
     */
    public <T> T get(Class<T> key) {
        return key.cast(state.get(key));
    }

    /**
     * Retrieves the state object associated with the given key type,
     * creating it with the factory if not yet present.
     * @param key The class used as the key.
     * @param factory A supplier to create the state object if absent.
     * @param <T> The type of the state object.
     * @return The existing or newly created state object.
     */
    public <T> T getOrCreate(Class<T> key, Supplier<T> factory) {
        return key.cast(state.computeIfAbsent(key, k -> factory.get()));
    }

    /**
     * Stores a state object associated with the given key type.
     * @param key The class used as the key.
     * @param value The state object to store.
     * @param <T> The type of the state object.
     */
    public <T> void put(Class<T> key, T value) {
        state.put(key, value);
    }
}
