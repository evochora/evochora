package org.evochora.compiler.frontend.semantics;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds per-module data in the module-aware symbol table.
 * Each module has its own symbol namespace, import aliases, require declarations,
 * and USING bindings.
 */
public final class ModuleScope {

    private final ModuleId moduleId;
    private final String sourcePath;
    private final Map<String, Symbol> symbols = new HashMap<>();
    private final Map<String, ModuleId> imports = new HashMap<>();
    private final Map<String, String> requires = new HashMap<>();
    private final Map<String, ModuleId> usingBindings = new HashMap<>();

    public ModuleScope(ModuleId moduleId, String sourcePath) {
        this.moduleId = moduleId;
        this.sourcePath = sourcePath;
    }

    public ModuleId moduleId() {
        return moduleId;
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
     * Alias (upper-case) to imported module ID.
     */
    public Map<String, ModuleId> imports() {
        return imports;
    }

    /**
     * Alias (upper-case) to required path/URL.
     */
    public Map<String, String> requires() {
        return requires;
    }

    /**
     * Alias (upper-case) to resolved module ID (filled by the importer via USING clauses).
     */
    public Map<String, ModuleId> usingBindings() {
        return usingBindings;
    }
}
