package org.evochora.compiler.features.conditional;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;
import org.evochora.compiler.frontend.preprocessor.BlockKind;

import java.util.Set;

/**
 * Compiler feature for conditional compilation: flags set with {@code .DEFINE NAME [integer]}
 * and removed with {@code .UNDEF NAME}, and blocks {@code .IFDEF} / {@code .IFNDEF} …
 * {@code .ELSEIFDEF} / {@code .ELSEIFNDEF} / {@code .ELSEDEF} … {@code .ENDDEF} that keep the
 * lines of the first branch whose condition holds. A condition tests whether a flag is set, and
 * may compare its value with {@code =} or {@code ==}, {@code <>} or {@code !=}, {@code <}, {@code <=},
 * {@code >} or {@code >=}.
 *
 * <p>Registers the comparison symbols; the preprocessor handlers for the four directives,
 * with {@code .DEFINE} and {@code .UNDEF} as directives that stand only at the top level and the
 * conditional block as a block processed in place; and the dependency scan handlers that follow
 * the same branches in Phase 0, so that a dependency in a branch that is not taken is never
 * loaded.</p>
 */
public class ConditionalFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "conditional";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        for (Condition.Operator operator : Condition.Operator.values()) {
            operator.symbols().forEach(ctx::lexerSymbol);
        }
        ctx.dependencyScanHandler(new DefineScanHandler());
        ctx.dependencyScanHandler(new UndefScanHandler());
        ctx.dependencyScanHandler(new ConditionalScanHandler());
        ctx.preprocessorBlock(new BlockKind(Set.of(".IFDEF", ".IFNDEF"), ".ENDDEF",
                Set.of(".ELSEIFDEF", ".ELSEIFNDEF", ".ELSEDEF"), false));
        ctx.preprocessorTopLevelOnly(".DEFINE");
        ctx.preprocessorTopLevelOnly(".UNDEF");
        ctx.preprocessor(".DEFINE", new DefineHandler());
        ctx.preprocessor(".UNDEF", new UndefHandler());
        ConditionalBlockHandler block = new ConditionalBlockHandler();
        ctx.preprocessor(".IFDEF", block);
        ctx.preprocessor(".IFNDEF", block);
    }
}
