package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.parser.ast.PopCtxNode;
import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrDirective;

import java.util.Collections;

public class PopCtxNodeConverter implements IAstNodeToIrConverter<PopCtxNode> {
    @Override
    public void convert(PopCtxNode node, IrGenContext context) {
        context.popAliasChain();
        context.emit(new IrDirective("core", "pop_ctx", Collections.emptyMap(), context.sourceOf(node)));
    }
}
