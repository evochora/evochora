package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.InternalCompilerException;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a caller of the compiler can rely on beyond the artifact: a defect in the compiler
 * reaches it as an {@link InternalCompilerException}, a {@link CompilationException} like a
 * program's mistake but told apart by type and by a message that names the exception instead
 * of a source position; and an instance carries nothing from one compilation into the next.
 */
@Tag("unit")
class CompilerDefectTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aCompilerInstanceForgetsTheErrorsOfAnEarlierCompilation() throws Exception {
        Compiler compiler = new Compiler();
        EnvironmentProperties world = new EnvironmentProperties(new int[]{100, 100}, true);

        assertThatThrownBy(() -> compiler.compile(List.of("START:", "  SETI %DR0"), "broken.evo", world))
                .isInstanceOf(CompilationException.class);
        compiler.compile(List.of("START:", "  NOP"), "fine.evo", world);
    }

    @Test
    void anExceptionAHandlerDidNotReportBecomesAnInternalCompilerError() {
        List<ICompilerFeature> features = new ArrayList<>(StandardFeatures.all());
        features.add(new ICompilerFeature() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void register(IFeatureRegistrationContext ctx) {
                ctx.parserStatement(".BROKEN", context -> {
                    throw new NullPointerException("defect in a handler");
                });
            }
        });
        Compiler compiler = new Compiler(features);

        assertThatThrownBy(() -> compiler.compile(List.of(".BROKEN", "START:", "  NOP"), "main.evo"))
                .isInstanceOf(InternalCompilerException.class)
                .hasMessageContaining("Internal compiler error")
                .hasMessageContaining("NullPointerException")
                .hasMessageContaining("defect in a handler")
                .hasCauseInstanceOf(NullPointerException.class);
    }
}
