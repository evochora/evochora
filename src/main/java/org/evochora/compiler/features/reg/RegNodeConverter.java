package org.evochora.compiler.features.reg;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.Map;

/**
 * Emits a {@code reg_alias} IR directive from a {@code .REG} AST node.
 *
 * <p>The directive carries the module-qualified alias name and the target register
 * name so that Phase 11 ({@link RegisterAliasEmissionContributor})
 * can include the alias mapping in the final {@link org.evochora.compiler.api.ProgramArtifact}.</p>
 */
public final class RegNodeConverter implements IAstNodeToIrConverter<RegNode> {

    @Override
    public void convert(RegNode node, IrGenContext ctx) {
        String qualifiedName = ctx.qualifyName(node.alias());
        String registerName = node.register();

        ctx.emit(new IrDirective("reg", "reg_alias", Map.of(
                "name", new IrValue.Str(qualifiedName),
                "register", new IrValue.Str(registerName)
        ), ctx.sourceOf(node)));
    }
}
