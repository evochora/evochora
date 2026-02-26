package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.nio.file.Path;

/**
 * Collects require aliases during pass 1: registers the alias-to-file mapping
 * in the symbol table for cross-file symbol resolution.
 */
public class RequireSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        RequireNode req = (RequireNode) node;
        if (req.alias() != null && req.path() != null && req.path().value() instanceof String) {
            String aliasU = req.alias().text().toUpperCase();
            String file = req.alias().fileName();
            String target = (String) req.path().value();
            String normalizedTarget = Path.of(target).normalize().toString().replace('\\', '/');
            symbolTable.registerRequireAlias(file, aliasU, normalizedTarget);
        }
    }
}
