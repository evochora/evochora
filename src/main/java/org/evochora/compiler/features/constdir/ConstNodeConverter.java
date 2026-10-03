package org.evochora.compiler.features.constdir;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Emits a {@code const_value} IR directive from a {@code .CONST} AST node. A constant places no
 * code: every reference to it was replaced by its value in the post-processing phase. The
 * directive carries the module-qualified name of the constant, the scope it is defined in and
 * its value as text, so that
 * Phase 11 ({@link ConstantValueEmissionContributor}) can include the value in the final
 * {@link org.evochora.compiler.api.ProgramArtifact} for the source view.
 *
 * <p>The name is qualified as a use of the constant is in the token map: the placement's alias
 * chain and the upper-cased name. The value is the value of the definition as its parser read
 * it: a typed literal as {@code TYPE:value}, a vector as its components joined by {@code |},
 * another constant or a label by its name, numbers in decimal.</p>
 */
public final class ConstNodeConverter implements IAstNodeToIrConverter<ConstNode> {

    @Override
    public void convert(ConstNode node, IrGenContext ctx) {
        if (!(node.value() instanceof OperandNode value)) {
            return;
        }
        ctx.emit(new IrDirective("constdir", "const_value", Map.of(
                "name", new IrValue.Str(ctx.qualifyName(node.name())),
                "scope", new IrValue.Str(ctx.currentScope()),
                "value", new IrValue.Str(text(value))
        ), ctx.sourceOf(node)));
    }

    private static String text(OperandNode value) {
        return switch (value) {
            case NumberLiteralNode number -> String.valueOf(number.value());
            case TypedLiteralNode typed -> typed.typeName() + ":" + typed.value();
            case VectorLiteralNode vector -> vector.values().stream().map(String::valueOf).collect(Collectors.joining("|"));
            case IdentifierNode identifier -> identifier.text();
            case RegisterNode register -> register.name();
        };
    }
}
