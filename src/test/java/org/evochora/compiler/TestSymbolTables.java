package org.evochora.compiler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.expression.ReservedNames;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Builds symbol tables for tests that use one without the names a {@link Compiler} reserves.
 */
public final class TestSymbolTables {

    private TestSymbolTables() {}

    /**
     * Builds a symbol table that reserves no name. The current module must be set via
     * {@link SymbolTable#setCurrentModule(String)} before any define/resolve operations.
     *
     * @param diagnostics The diagnostics engine for reporting errors.
     * @return The symbol table, with no reserved names.
     */
    public static SymbolTable symbolTable(DiagnosticsEngine diagnostics) {
        return new SymbolTable(diagnostics, ReservedNames.NONE);
    }
}
