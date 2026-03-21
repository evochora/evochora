package org.evochora.compiler.frontend.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A generic type-safe container for parser state that features can use
 * to store and retrieve their own state objects during parsing.
 * Each feature defines its own state class and uses it as the key.
 */
public class ParserState {

    private final Map<Class<?>, Object> state = new HashMap<>();
    private final List<IScopedParserState> scopedStates = new ArrayList<>();
    private final Map<String, Integer> availableRegisterBanks = new HashMap<>(Map.of("DR", 1, "LR", 1));

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
        return key.cast(state.computeIfAbsent(key, k -> {
            T instance = factory.get();
            if (instance instanceof IScopedParserState scoped) {
                scopedStates.add(scoped);
            }
            return instance;
        }));
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

    /**
     * Makes register banks available in the current scope. Uses reference counting so that
     * nested scopes adding the same bank do not interfere — a bank remains available until
     * all scopes that added it have removed it.
     *
     * @param banks the bank prefixes to make available (e.g., "PDR")
     */
    public void addAvailableRegisterBanks(String... banks) {
        for (String bank : banks) {
            availableRegisterBanks.merge(bank, 1, Integer::sum);
        }
    }

    /**
     * Removes register banks from the current scope. Decrements the reference count; the bank
     * becomes unavailable only when the count reaches zero.
     *
     * @param banks the bank prefixes to remove (e.g., "PDR")
     */
    public void removeAvailableRegisterBanks(String... banks) {
        for (String bank : banks) {
            availableRegisterBanks.computeIfPresent(bank, (k, count) -> count <= 1 ? null : count - 1);
        }
    }

    /**
     * Checks whether a register bank is currently available for use in directives like {@code .REG}.
     *
     * @param bank the bank prefix (e.g., "DR", "PDR")
     * @return {@code true} if the bank is available in the current scope
     */
    public boolean isRegisterBankAvailable(String bank) {
        return availableRegisterBanks.getOrDefault(bank, 0) > 0;
    }

    /**
     * Pushes a new scope on all registered {@link IScopedParserState} objects.
     */
    public void pushScope() {
        for (IScopedParserState s : scopedStates) {
            s.pushScope();
        }
    }

    /**
     * Pops the current scope from all registered {@link IScopedParserState} objects.
     */
    public void popScope() {
        for (IScopedParserState s : scopedStates) {
            s.popScope();
        }
    }
}
