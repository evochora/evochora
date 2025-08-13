package org.evochora.compiler.core.phases;

import org.evochora.compiler.core.ast.*;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.util.List;

/**
 * Führt die semantische Analyse des Abstract Syntax Tree durch.
 * Prüft auf logische Fehler, die der Parser nicht erkennen kann (z.B. Typfehler,
 * falsche Argumentanzahl, undefinierte Variablen).
 */
public class SemanticAnalyzer implements AstVisitor<Void> {

    private final DiagnosticsEngine diagnostics;

    public SemanticAnalyzer(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
    }

    /**
     * Startet die Analyse für eine Liste von AST-Knoten.
     * @param statements Die zu analysierenden Top-Level-Statements.
     */
    public void analyze(List<AstNode> statements) {
        for (AstNode statement : statements) {
            statement.accept(this);
        }
    }

    @Override
    public Void visit(InstructionNode node) {
        // TODO: [Phase 4] Implementierungslogik hier einfügen.
        return null;
    }

    @Override
    public Void visit(LabelNode node) {
        node.statement().accept(this); // Analysiere das Statement, das zum Label gehört.
        return null;
    }

    @Override
    public Void visit(ProcedureNode node) {
        // TODO: [Phase 4] Semantik für Prozeduren (z.B. Scopes) hier behandeln.
        for (AstNode statement : node.body()) {
            statement.accept(this);
        }
        return null;
    }

    @Override
    public Void visit(ScopeNode node) {
        // TODO: [Phase 4] Semantik für Scopes hier behandeln.
        for (AstNode statement : node.body()) {
            statement.accept(this);
        }
        return null;
    }

    // Direktiven, die erst in der Linker-Phase relevant werden.
    @Override public Void visit(ExportNode node) { return null; }
    @Override public Void visit(RequireNode node) { return null; }
    @Override public Void visit(PregNode node) { return null; }
    @Override public Void visit(OrgNode node) { return null; }
    @Override public Void visit(DirNode node) { return null; }
    @Override public Void visit(PlaceNode node) { return null; }

    // Besuchsmethoden für Literale und Blätter des Baumes (tun meistens nichts).
    @Override public Void visit(NumberLiteralNode node) { return null; }
    @Override public Void visit(TypedLiteralNode node) { return null; }
    @Override public Void visit(VectorLiteralNode node) { return null; }
    @Override public Void visit(RegisterNode node) { return null; }
    @Override public Void visit(IdentifierNode node) { return null; }
}
