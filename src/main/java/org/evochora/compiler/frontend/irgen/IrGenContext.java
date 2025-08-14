package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
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

/**
 * Mutable context passed to converters during IR generation.
 * Provides emission utilities, diagnostics access, and SourceInfo construction.
 */
public final class IrGenContext {

	private final String programName;
	private final DiagnosticsEngine diagnostics;
	private final IrConverterRegistry registry;
	private final List<IrItem> out = new ArrayList<>();

	/**
	 * Creates a new IR generation context for the given program.
	 *
	 * @param programName The program name used for diagnostics and IR metadata.
	 * @param diagnostics The diagnostics engine to report errors and warnings.
	 */
    public IrGenContext(String programName, DiagnosticsEngine diagnostics, IrConverterRegistry registry) {
		this.programName = programName;
		this.diagnostics = diagnostics;
		this.registry = registry;
	}

	/**
	 * Emits a single IR item to the output stream.
	 *
	 * @param item The IR item to emit.
	 */
	public void emit(IrItem item) {
		out.add(item);
	}

	/**
	 * Converts and emits the given child AST node using the registry.
	 *
	 * @param node The AST node to convert.
	 */
	public void convert(AstNode node) {
		registry.resolve(node).convert(node, this);
	}

	/**
	 * @return The diagnostics engine for reporting issues during IR generation.
	 */
	public DiagnosticsEngine diagnostics() {
		return diagnostics;
	}

	/**
	 * Builds a SourceInfo instance for the given AST node.
	 * Note: This can be enhanced later to pull accurate file/line/content from tokens on each node type.
	 *
	 * @param node The AST node to extract source information from.
	 * @return A SourceInfo instance associated with the node.
	 */
    public SourceInfo sourceOf(AstNode node) {
        // Try to choose a representative token for the node
        String file = "unknown";
        int line = -1;
        String text = "";

        if (node instanceof InstructionNode n) {
            if (n.opcode() != null) { file = n.opcode().fileName(); line = n.opcode().line(); text = n.opcode().text(); }
        } else if (node instanceof LabelNode n) {
            if (n.labelToken() != null) { file = n.labelToken().fileName(); line = n.labelToken().line(); text = n.labelToken().text(); }
        } else if (node instanceof RegisterNode n) {
            if (n.registerToken() != null) { file = n.registerToken().fileName(); line = n.registerToken().line(); text = n.registerToken().text(); }
        } else if (node instanceof NumberLiteralNode n) {
            if (n.numberToken() != null) { file = n.numberToken().fileName(); line = n.numberToken().line(); text = n.numberToken().text(); }
        } else if (node instanceof TypedLiteralNode n) {
            if (n.type() != null) { file = n.type().fileName(); line = n.type().line(); text = n.type().text(); }
        } else if (node instanceof IdentifierNode n) {
            if (n.identifierToken() != null) { file = n.identifierToken().fileName(); line = n.identifierToken().line(); text = n.identifierToken().text(); }
        } else if (node instanceof VectorLiteralNode n) {
            if (!n.components().isEmpty() && n.components().get(0) != null) {
                var t = n.components().get(0);
                file = t.fileName(); line = t.line(); text = t.text();
            }
        } else if (node instanceof OrgNode || node instanceof DirNode || node instanceof PlaceNode) {
            // These wrap expressions; fallback: unknown. Their child nodes will produce better SourceInfo.
        } else if (node instanceof ProcedureNode n) {
            if (n.name() != null) { file = n.name().fileName(); line = n.name().line(); text = n.name().text(); }
        } else if (node instanceof ScopeNode n) {
            if (n.name() != null) { file = n.name().fileName(); line = n.name().line(); text = n.name().text(); }
        }

        return new SourceInfo(file, line, text);
    }

	/**
	 * Finalizes and returns an immutable IR program with all emitted items.
	 *
	 * @return The generated IR program.
	 */
	public IrProgram build() {
		return new IrProgram(programName, List.copyOf(out));
	}
}


