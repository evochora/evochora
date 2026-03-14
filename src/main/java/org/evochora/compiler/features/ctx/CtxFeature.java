package org.evochora.compiler.features.ctx;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;

/**
 * Compiler feature for internal context tracking directives ({@code .PUSH_CTX}, {@code .POP_CTX}).
 *
 * <p>These directives are injected by the preprocessor when processing {@code .SOURCE} and
 * {@code .IMPORT} inclusions. They propagate module context (alias chains, layout positions)
 * through the compiler pipeline so that each phase can track which module is currently active.</p>
 */
public class CtxFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "ctx";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        // Phase 2: Preprocessing
        ctx.preprocessor(".POP_CTX", new PopCtxPreProcessorHandler());

        // Phase 3: Parsing
        ctx.parser(".PUSH_CTX", new PushCtxDirectiveHandler());
        ctx.parser(".POP_CTX", new PopCtxDirectiveHandler());

        // Phase 7: IR Generation
        ctx.irConverter(PushCtxNode.class, new PushCtxNodeConverter());
        ctx.irConverter(PopCtxNode.class, new PopCtxNodeConverter());

        // Phase 9: Layout
        ctx.layoutHandler("core", "push_ctx", new PushCtxLayoutHandler());
        ctx.layoutHandler("core", "pop_ctx", new PopCtxLayoutHandler());
    }
}
