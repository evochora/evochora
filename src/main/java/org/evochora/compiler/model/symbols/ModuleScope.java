package org.evochora.compiler.model.symbols;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The namespace of one module placement: the symbols it defines, the modules it imports and under
 * which alias, which of those imports it passes on, the bindings it received through USING, and
 * the paths it requires. Each is written through a method that keeps the namespace's rule (the
 * first symbol of a name stays; an alias binds once) and read through an unmodifiable view.
 * <p>
 * Semantic analysis fills a scope and then freezes it; after that every write is a defect.
 */
public final class ModuleScope {
    private final String aliasChain;
    private final String sourcePath;
    private final Map<String, Symbol> symbols = new HashMap<>();
    private final Map<String, String> imports = new HashMap<>();       // alias → alias chain of imported module
    private final Map<String, String> requires = new HashMap<>();      // alias → required path/URL
    private final Map<String, String> usingBindings = new HashMap<>(); // alias → alias chain of resolved module
    private final Map<String, Boolean> importExported = new HashMap<>(); // alias → exported flag
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
     * @return The import alias chain that names this placement, empty for the root module.
     */
    public String aliasChain() {
        return aliasChain;
    }

    /**
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
     * Records that this module imports another under an alias, and whether it passes the import
     * on to its own importers. An alias binds once: the first module stays, and a later call
     * for the same alias is only taken if it names the same module.
     *
     * @param alias      The alias the imported module is known by here.
     * @param aliasChain The alias chain of the imported module's placement.
     * @param exported   Whether names may reach through this import from outside.
     * @return {@code true} if the alias now names that module; {@code false} if it names
     *         another one already, which the phase that knows the source line reports.
     * @throws IllegalStateException if the scope is frozen.
     */
    public boolean addImport(String alias, String aliasChain, boolean exported) {
        guardFrozen();
        if (!bindOnce(imports, alias, aliasChain)) {
            return false;
        }
        importExported.put(alias, exported);
        return true;
    }

    /**
     * Records that this module requires a module its importer has to supply. An alias binds
     * once, as for {@link #addImport}.
     *
     * @param alias The alias the required module is known by here.
     * @param path  The path the requirement names.
     * @return {@code true} if the alias now names that path; {@code false} if it names another.
     * @throws IllegalStateException if the scope is frozen.
     */
    public boolean addRequirement(String alias, String path) {
        guardFrozen();
        return bindOnce(requires, alias, path);
    }

    /**
     * Records which module an importer supplied for one of this module's requirements. An
     * alias binds once, as for {@link #addImport}.
     *
     * @param alias      The alias of the requirement, as this module names it.
     * @param aliasChain The alias chain of the placement the importer supplied.
     * @return {@code true} if the alias now names that placement; {@code false} if it names another.
     * @throws IllegalStateException if the scope is frozen.
     */
    public boolean bindUsing(String alias, String aliasChain) {
        guardFrozen();
        return bindOnce(usingBindings, alias, aliasChain);
    }

    private static boolean bindOnce(Map<String, String> map, String alias, String target) {
        String bound = map.putIfAbsent(alias, target);
        return bound == null || bound.equals(target);
    }

    /**
     * @return The symbols this module defines, by name; unmodifiable.
     */
    public Map<String, Symbol> symbols() {
        return Collections.unmodifiableMap(symbols);
    }

    /**
     * @return The alias chains of the modules this module imports, by alias; unmodifiable.
     */
    public Map<String, String> imports() {
        return Collections.unmodifiableMap(imports);
    }

    /**
     * @return The paths this module requires, by alias; unmodifiable.
     */
    public Map<String, String> requires() {
        return Collections.unmodifiableMap(requires);
    }

    /**
     * @return The placements this module received for its requirements, by alias; unmodifiable.
     */
    public Map<String, String> usingBindings() {
        return Collections.unmodifiableMap(usingBindings);
    }

    /**
     * @return Whether each import is passed on to this module's importers, by alias; unmodifiable.
     */
    public Map<String, Boolean> importExported() {
        return Collections.unmodifiableMap(importExported);
    }
}
