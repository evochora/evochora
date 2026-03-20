package org.evochora.compiler.features.ctx;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ir.IrDirective;

import java.util.Collections;

/**
 * IR converter for {@link PopCtxNode} (Phase 7). Emits a {@code core:pop_ctx}
 * IR directive and pops the alias chain from the IR generation context stack.
 */
public class PopCtxNodeConverter implements IAstNodeToIrConverter<PopCtxNode> {
    @Override
    public void convert(PopCtxNode node, IrGenContext context) {
        context.popAliasChain();
        context.emit(new IrDirective("core", "pop_ctx", Collections.emptyMap(), context.sourceOf(node)));
    }
}
