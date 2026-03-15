package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.features.reg.RegNode;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.Map;

/**
 * Emits a {@code reg_alias} IR directive from a {@code .REG} AST node.
 *
 * <p>The directive carries the module-qualified alias name and the target register
 * name so that Phase 11 ({@link org.evochora.compiler.backend.emit.RegisterAliasEmissionContributor})
 * can include the alias mapping in the final {@link org.evochora.compiler.api.ProgramArtifact}.</p>
 */
public final class RegNodeConverter implements IAstNodeToIrConverter<RegNode> {

    @Override
    public void convert(RegNode node, IrGenContext ctx) {
        String qualifiedName = ctx.qualifyName(node.alias().text());
        String registerName = node.register().text();

        ctx.emit(new IrDirective("reg", "reg_alias", Map.of(
                "name", new IrValue.Str(qualifiedName),
                "register", new IrValue.Str(registerName)
        ), ctx.sourceOf(node)));
    }
}
