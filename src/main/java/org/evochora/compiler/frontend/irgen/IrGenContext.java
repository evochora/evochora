package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.IdentifierNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.ast.NumberLiteralNode;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.org.OrgNode;
import org.evochora.compiler.frontend.parser.features.dir.DirNode;
import org.evochora.compiler.frontend.parser.features.place.PlaceNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.compiler.ir.IrItem;
import org.evochora.compiler.ir.IrProgram;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mutable context passed to converters during IR generation.
 * Provides emission utilities, diagnostics access, and SourceInfo construction.
 */
public final class IrGenContext {

    private final String programName;
    private final DiagnosticsEngine diagnostics;
    private final IrConverterRegistry registry;
    private final List<IrItem> out = new ArrayList<>();

    public IrGenContext(String programName, DiagnosticsEngine diagnostics, IrConverterRegistry registry) {
        this.programName = programName;
        this.diagnostics = diagnostics;
        this.registry = registry;
    }

    public void emit(IrItem item) {
        out.add(item);
    }

    public void convert(AstNode node) {
        registry.resolve(node).convert(node, this);
    }

    public DiagnosticsEngine diagnostics() {
        return diagnostics;
    }

    // --- START DER KORREKTUR ---

    /**
     * Stellt den Text einer kompletten Instruktionszeile aus dem AST-Knoten wieder her.
     * @param node Der AST-Knoten der Instruktion.
     * @return Ein String, der die komplette Zeile repräsentiert.
     */
    private String reconstructLineFromInstruction(InstructionNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.opcode().text());

        for (AstNode arg : node.arguments()) {
            sb.append(" ");
            if (arg instanceof RegisterNode rn) {
                sb.append(rn.registerToken().text());
            } else if (arg instanceof NumberLiteralNode nn) {
                sb.append(nn.numberToken().text());
            } else if (arg instanceof TypedLiteralNode tn) {
                sb.append(tn.type().text()).append(":").append(tn.value().text());
            } else if (arg instanceof VectorLiteralNode vn) {
                String vec = vn.components().stream().map(Token::text).collect(Collectors.joining("|"));
                sb.append(vec);
            } else if (arg instanceof IdentifierNode in) {
                sb.append(in.identifierToken().text());
            }
        }
        return sb.toString();
    }

    public SourceInfo sourceOf(AstNode node) {
        if (node instanceof InstructionNode n && n.opcode() != null) {
            // Für Instruktionen rekonstruieren wir die Zeile.
            return new SourceInfo(
                    n.opcode().fileName(),
                    n.opcode().line(),
                    reconstructLineFromInstruction(n)
            );
        }

        // Für alle anderen Knoten verwenden wir weiterhin einen repräsentativen Token.
        Token representative = getRepresentativeToken(node);
        if (representative != null) {
            return new SourceInfo(representative.fileName(), representative.line(), representative.text());
        }

        return new SourceInfo("unknown", -1, "");
    }

    private Token getRepresentativeToken(AstNode node) {
        if (node instanceof LabelNode n) return n.labelToken();
        if (node instanceof RegisterNode n) return n.registerToken();
        if (node instanceof NumberLiteralNode n) return n.numberToken();
        if (node instanceof TypedLiteralNode n) return n.type();
        if (node instanceof IdentifierNode n) return n.identifierToken();
        if (node instanceof ProcedureNode n) return n.name();
        if (node instanceof ScopeNode n) return n.name();
        if (node instanceof VectorLiteralNode n && !n.components().isEmpty()) return n.components().get(0);
        return null;
    }

    // --- ENDE DER KORREKTUR ---

    public IrProgram build() {
        return new IrProgram(programName, List.copyOf(out));
    }
}