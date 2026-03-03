package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.parser.ast.PushCtxNode;
import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.HashMap;
import java.util.Map;

public class PushCtxNodeConverter implements IAstNodeToIrConverter<PushCtxNode> {
    @Override
    public void convert(PushCtxNode node, IrGenContext context) {
        Map<String, IrValue> args = new HashMap<>();
        if (node.aliasChain() != null) {
            args.put("aliasChain", new IrValue.Str(node.aliasChain()));
        }
        // Always push so pop is symmetric. For .SOURCE (null aliasChain),
        // pushAliasChain(null) preserves the current chain on the stack.
        context.pushAliasChain(node.aliasChain());
        context.emit(new IrDirective("core", "push_ctx", args, context.sourceOf(node)));
    }
}
