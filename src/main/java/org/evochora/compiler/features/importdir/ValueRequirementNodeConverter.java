package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.OperandText;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.Map;

/**
 * Emits an {@code importdir} {@code value} IR directive from a value requirement. The
 * requirement places no code: every use of the value was replaced by the value in phase 6. The
 * directive carries the path of the value in its placement and the literal phase 6 put into the
 * value slot, written as {@link OperandText} writes it, so that phase 11
 * ({@link ValueEmissionContributor}) can include the value in the
 * {@link org.evochora.compiler.api.ProgramArtifact} for the source view. A slot that is still an
 * identifier stands for a value that was reported missing, and emits nothing.
 */
public final class ValueRequirementNodeConverter implements IAstNodeToIrConverter<ValueRequirementNode> {

    @Override
    public void convert(ValueRequirementNode node, IrGenContext ctx) {
        if (!(node.value() instanceof OperandNode value) || value instanceof IdentifierNode) {
            return;
        }
        ctx.emit(new IrDirective("importdir", "value", Map.of(
                "name", new IrValue.Str(ctx.qualifyName(node.name())),
                "value", new IrValue.Str(OperandText.of(value))
        ), ctx.sourceOf(node)));
    }
}
