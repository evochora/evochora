package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.ir.IrDirective;

import java.util.Collections;

public class PopCtxNodeConverter implements IAstNodeToIrConverter {
    @Override
    public void convert(AstNode node, IrGenContext context) {
        context.emit(new IrDirective("core", "pop_ctx", Collections.emptyMap(), context.sourceOf(node)));
    }
}
