package org.evochora.compiler.features.constdir;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Collects the name of a {@code .CONST} during pass 1: defines the constant on the level it
 * stands in, with the node itself as the symbol's node, so that a reference to the constant
 * anywhere on that level, above or below the line, can be replaced by its value. A name defined
 * twice on one level is reported at the second definition, naming the first. The value is the
 * business of pass 2.
 */
public class ConstSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ConstNode constNode = (ConstNode) node;
        symbolTable.define(new Symbol(constNode.name(), constNode.sourceInfo(), Symbol.Type.CONSTANT, constNode, constNode.exported()))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot define constant '" + constNode.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        constNode.sourceInfo().fileName(), constNode.sourceInfo().lineNumber()));
    }
}
