package org.evochora.compiler.model.symbols;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds per-module data in the module-aware symbol table.
 * Each module has its own symbol namespace, import aliases, require declarations,
 * and USING bindings. Modules are identified by their alias chain (e.g., "PRED.MATH").
 */
public final class ModuleScope {

    private final String aliasChain;
    private final String sourcePath;
    private final Map<String, Symbol> symbols = new HashMap<>();
    private final Map<String, String> imports = new HashMap<>();       // alias → alias chain of imported module
    private final Map<String, String> requires = new HashMap<>();      // alias → required path/URL
    private final Map<String, String> usingBindings = new HashMap<>(); // alias → alias chain of resolved module
    private final Map<String, Boolean> importExported = new HashMap<>(); // alias → exported flag

    public ModuleScope(String aliasChain, String sourcePath) {
        this.aliasChain = aliasChain;
        this.sourcePath = sourcePath;
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
        return symbols;
    }

    /**
     * Alias (upper-case) to alias chain of imported module.
     */
    public Map<String, String> imports() {
        return imports;
    }

    /**
     * Alias (upper-case) to required path/URL.
     */
    public Map<String, String> requires() {
        return requires;
    }

    /**
     * Alias (upper-case) to alias chain of resolved module (filled by the importer via USING clauses).
     */
    public Map<String, String> usingBindings() {
        return usingBindings;
    }

    /**
     * Alias (upper-case) to whether the import is exported.
     */
    public Map<String, Boolean> importExported() {
        return importExported;
    }
}
