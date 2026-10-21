package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.QualifiedNames;
import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.Map;

/**
 * IR converter for {@code .IMPORT} directives. The imported module's IR items are merged into the
 * program during the multi-module IR merge step in the Compiler, not here.
 *
 * <p>A bound value that is still a computation after phase 6 is one only the layout computes,
 * where the import line stands, with the importer's cursor and frame: the converter emits one
 * {@code importdir} {@code bound_value} directive per such binding, carrying the qualified name of
 * the requirement it binds, the imported placement's alias chain joined with the binding's name,
 * and the tree. The requirement's own line names itself as its value. A bound value that phase 6
 * resolved is carried by the requirement's line and emits nothing here.</p>
 */
public class ImportNodeConverter implements IAstNodeToIrConverter<ImportNode> {

    @Override
    public void convert(ImportNode node, IrGenContext ctx) {
        String importedAliasChain = QualifiedNames.join(ctx.currentAliasChain(), node.alias());
        for (ImportNode.ValueBinding binding : node.values()) {
            if (binding.value() instanceof ExpressionNode expression) {
                ctx.emit(new IrDirective("importdir", "bound_value", Map.of(
                        "name", new IrValue.Str(QualifiedNames.join(importedAliasChain, binding.name())),
                        "value", ctx.convertValue(expression)
                ), expression.sourceInfo()));
            }
        }
    }
}
