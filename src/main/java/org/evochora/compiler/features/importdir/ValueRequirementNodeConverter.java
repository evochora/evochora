package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.OperandText;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.Map;

/**
 * Emits an {@code importdir} {@code value} IR directive from a value requirement. The
 * requirement places no code: every use of the value was replaced by the value in phase 6. The
 * directive carries the path of the value in its placement and what phase 6 put into the value
 * slot, written as {@link OperandText} writes it: a literal, or the qualified name of a label when
 * the value names one, as a constant that names a label is written. Phase 11
 * ({@link ValueEmissionContributor}) includes the value in the
 * {@link org.evochora.compiler.api.ProgramArtifact} for the source view. A value that was never
 * bound does not reach this phase: the analysis reported it and the compilation ended there.
 */
public final class ValueRequirementNodeConverter implements IAstNodeToIrConverter<ValueRequirementNode> {

    @Override
    public void convert(ValueRequirementNode node, IrGenContext ctx) {
        if (!(node.value() instanceof OperandNode value)) {
            return;
        }
        ctx.emit(new IrDirective("importdir", "value", Map.of(
                "name", new IrValue.Str(ctx.qualifyName(node.name())),
                "value", new IrValue.Str(OperandText.of(value))
        ), ctx.sourceOf(node)));
    }
}
