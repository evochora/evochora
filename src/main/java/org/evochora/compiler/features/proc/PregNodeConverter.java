package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;

import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.Map;

/**
 * Emits a {@code reg_alias} IR directive from a {@code .PREG} AST node.
 *
 * <p>Uses the same {@code reg_alias} directive name as {@link RegNodeConverter}
 * since both carry identical data (qualified alias name + target register name).
 * Phase 11 processes them uniformly.</p>
 */
public final class PregNodeConverter implements IAstNodeToIrConverter<PregNode> {

    @Override
    public void convert(PregNode node, IrGenContext ctx) {
        String qualifiedName = ctx.qualifyName(node.alias().text());
        String registerName = node.targetRegister().text();

        ctx.emit(new IrDirective("reg", "reg_alias", Map.of(
                "name", new IrValue.Str(qualifiedName),
                "register", new IrValue.Str(registerName)
        ), ctx.sourceOf(node)));
    }
}
