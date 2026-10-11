package org.evochora.compiler.model.symbols;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The namespace of one module placement: its symbols, its aliases and the records of features.
 * An alias of the module level leads to another placement, whoever bound it there. A feature keeps
 * what it needs to know about the placement in a record of its own type; the core does not know
 * what a feature keeps and never reads it. Each is written through a method that keeps the
 * namespace's rule (the first symbol of a name stays; an alias binds once; a record is created
 * once) and read through an unmodifiable view or the record itself.
 * <p>
 * Semantic analysis fills a scope and then freezes it; after that every write is a defect.
 */
public final class ModuleScope {
    private final String aliasChain;
    private final String sourcePath;
    private final Map<String, Symbol> symbols = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();   // alias → alias chain of the placement it leads to
    private final Map<Class<?>, Object> records = new HashMap<>(); // feature record by its type
    private boolean frozen;

    /**
     * Creates the namespace of a module placement.
     *
     * @param aliasChain The import alias chain that names the placement, empty for the root.
     * @param sourcePath The file the module was read from.
     */
    public ModuleScope(String aliasChain, String sourcePath) {
        this.aliasChain = aliasChain;
        this.sourcePath = sourcePath;
    }

    /**
     * Seals the namespace: from now on every write is a defect.
     */
    void freeze() {
        this.frozen = true;
    }

    private void guardFrozen() {
        if (frozen) {
            throw new IllegalStateException("Module scope '" + aliasChain + "' is frozen; nothing is defined after semantic analysis");
        }
    }

    /**
     * Names this placement.
     *
     * @return The import alias chain that names it, empty for the root module.
     */
    public String aliasChain() {
        return aliasChain;
    }

    /**
     * Names the module's file.
     *
     * @return The file the module was read from.
     */
    public String sourcePath() {
        return sourcePath;
    }

    /**
     * Defines a symbol under a name, unless the name has one already: the first definition stays.
     *
     * @param name   The name, as the namespace keys it.
     * @param symbol The symbol.
     * @return The symbol the name already had, which stays; empty if the name was free.
     * @throws IllegalStateException if the scope is frozen.
     */
    public Optional<Symbol> defineSymbol(String name, Symbol symbol) {
        guardFrozen();
        Symbol existing = symbols.putIfAbsent(name, symbol);
        return Optional.ofNullable(existing);
    }

    /**
     * Binds an alias of the module level to the placement it leads to. An alias binds once: the
     * first placement stays, and a later call for the same alias is only taken if it names the
     * same placement.
     *
     * @param alias      The alias, as the namespace keys it.
     * @param aliasChain The alias chain of the placement the alias leads to.
     * @return {@code true} if the alias now leads to that placement; {@code false} if it leads to
     *         another one already, which the phase that knows the source line reports.
     * @throws IllegalStateException if the scope is frozen.
     */
    public boolean bindAlias(String alias, String aliasChain) {
        guardFrozen();
        String bound = aliases.putIfAbsent(alias, aliasChain);
        return bound == null || bound.equals(aliasChain);
    }

    /**
     * Returns the record a feature keeps for this placement under the given key type, creating it
     * with the factory on the first request. One instance exists per key for the lifetime of the
     * scope; the core knows nothing of the type and never reads the object.
     *
     * @param key     The class used as the key.
     * @param factory Creates the record; called only while no record exists for the key.
     * @param <T>     The type of the record.
     * @return The existing or newly created record.
     * @throws IllegalStateException if no record exists for the key and the scope is frozen.
     */
    public <T> T state(Class<T> key, Supplier<T> factory) {
        Object existing = records.get(key);
        if (existing == null) {
            guardFrozen();
            existing = factory.get();
            records.put(key, existing);
        }
        return key.cast(existing);
    }

    /**
     * Returns the symbols this module defines.
     *
     * @return The symbols by name; unmodifiable.
     */
    public Map<String, Symbol> symbols() {
        return Collections.unmodifiableMap(symbols);
    }

    /**
     * Returns the aliases of the module level.
     *
     * @return The alias chains of the placements they lead to, by alias; unmodifiable.
     */
    public Map<String, String> aliases() {
        return Collections.unmodifiableMap(aliases);
    }
}
