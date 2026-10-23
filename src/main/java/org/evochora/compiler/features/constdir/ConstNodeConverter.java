package org.evochora.compiler.features.constdir;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.OperandText;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.Map;

/**
 * Emits a {@code const_value} IR directive from a {@code .CONST} AST node. A constant places no
 * code: every reference to it was replaced by its value in the post-processing phase. The
 * directive carries the path of the constant and its value as text, so that
 * Phase 11 ({@link ConstantValueEmissionContributor}) can include the value in the final
 * {@link org.evochora.compiler.api.ProgramArtifact} for the source view.
 *
 * <p>The name is qualified as a use of the constant is in the token map: the path of the scope
 * the constant is defined in, or the placement's alias chain on the module level, and the
 * upper-cased name. The value is the literal phase 6 resolved, written as {@link OperandText}
 * writes it; a constant that names a label carries the label's qualified name. A constant whose
 * value only the layout knows carries the tree that remains, as an {@link IrValue.Expression}.</p>
 */
public final class ConstNodeConverter implements IAstNodeToIrConverter<ConstNode> {

    @Override
    public void convert(ConstNode node, IrGenContext ctx) {
        if (!(node.value() instanceof OperandNode value)) {
            return;
        }
        ctx.emit(new IrDirective("constdir", "const_value", Map.of(
                "name", new IrValue.Str(ctx.qualifyName(node.name())),
                "value", value instanceof ExpressionNode ? ctx.convertValue(value) : new IrValue.Str(OperandText.of(value))
        ), ctx.sourceOf(node)));
    }
}
