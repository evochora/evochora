package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Interface für spezialisierte Handler in der semantischen Analyse.
 * Jeder Handler ist für die Analyse eines bestimmten Typs von AST-Knoten zuständig.
 */
@FunctionalInterface
public interface IAnalysisHandler {
    /**
     * Analysiert einen einzelnen AST-Knoten.
     * @param node Der zu analysierende Knoten.
     * @param symbolTable Die Symboltabelle für den aktuellen Scope.
     * @param diagnostics Die Engine zum Melden von Fehlern.
     */
    void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics);
}