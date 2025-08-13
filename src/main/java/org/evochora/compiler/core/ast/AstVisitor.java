package org.evochora.compiler.core.ast;

/**
 * Ein Visitor für den Abstract Syntax Tree.
 * Dies ist ein Standard-Design-Pattern, um Operationen auf einer Baumstruktur
 * sauber zu implementieren, ohne die Knotenklassen selbst zu verändern.
 *
 * @param <T> Der Rückgabetyp der visit-Methoden.
 */
public interface AstVisitor<T> {
    T visit(InstructionNode node);
    T visit(LabelNode node);
    T visit(ProcedureNode node);
    T visit(ScopeNode node);
    T visit(ImportNode node);
    T visit(ExportNode node);
    T visit(RequireNode node);
    T visit(PregNode node);
    T visit(OrgNode node);
    T visit(DirNode node);
    T visit(PlaceNode node);
    T visit(NumberLiteralNode node);
    T visit(TypedLiteralNode node);
    T visit(VectorLiteralNode node);
    T visit(RegisterNode node);
    T visit(IdentifierNode node);
}
