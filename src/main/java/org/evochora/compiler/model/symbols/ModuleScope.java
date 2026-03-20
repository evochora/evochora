package org.evochora.compiler.model.symbols;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds per-module data in the module-aware symbol table.
 * Each module has its own symbol namespace, import aliases, require declarations,
 * and USING bindings. Modules are identified by their alias chain (e.g., "PRED.MATH").
 *
 * <p>Supports a freeze protocol: before freeze, all map getters return the mutable
 * backing maps for Phase 4 setup. After {@link #freeze()}, they return unmodifiable
 * views, enforcing immutability for all subsequent phases.</p>
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

    public ModuleScope(String aliasChain, String sourcePath) {
        this.aliasChain = aliasChain;
        this.sourcePath = sourcePath;
    }

    /**
     * Freezes this module scope. After freeze, all map getters return
     * unmodifiable views, preventing structural modifications.
     */
    void freeze() {
        this.frozen = true;
    }

    public String aliasChain() {
        return aliasChain;
    }

    public String sourcePath() {
        return sourcePath;
    }

    /**
     * Unqualified name (upper-case) to Symbol.
     */
    public Map<String, Symbol> symbols() {
        return frozen ? Collections.unmodifiableMap(symbols) : symbols;
    }

    /**
     * Alias (upper-case) to alias chain of imported module.
     */
    public Map<String, String> imports() {
        return frozen ? Collections.unmodifiableMap(imports) : imports;
    }

    /**
     * Alias (upper-case) to required path/URL.
     */
    public Map<String, String> requires() {
        return frozen ? Collections.unmodifiableMap(requires) : requires;
    }

    /**
     * Alias (upper-case) to alias chain of resolved module (filled by the importer via USING clauses).
     */
    public Map<String, String> usingBindings() {
        return frozen ? Collections.unmodifiableMap(usingBindings) : usingBindings;
    }

    /**
     * Alias (upper-case) to whether the import is exported.
     */
    public Map<String, Boolean> importExported() {
        return frozen ? Collections.unmodifiableMap(importExported) : importExported;
    }
}
