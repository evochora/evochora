package org.evochora.compiler.frontend.semantics;

/**
 * Uniquely identifies a symbol across all modules.
 * Compiler-internal — does not flow into ProgramArtifact.
 *
 * @param module The module this symbol belongs to.
 * @param name   The unqualified, upper-case symbol name within the module.
 */
public record SymbolId(ModuleId module, String name) {

    @Override
    public String toString() {
        return module.path() + "::" + name;
    }
}
