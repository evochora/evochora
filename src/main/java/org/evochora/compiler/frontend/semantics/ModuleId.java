package org.evochora.compiler.frontend.semantics;

/**
 * Identifies a module by its resolved file path or URL.
 * Used as a map key in the module-aware symbol table.
 *
 * @param path The resolved, normalized file path or URL that uniquely identifies a module.
 */
public record ModuleId(String path) {

    @Override
    public String toString() {
        return path;
    }
}
