package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.InternalCompilerException;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A defect in the compiler reaches the caller as an {@link InternalCompilerException}: a
 * {@link CompilationException} like a program's mistake, but told apart by type and by a
 * message that names the exception instead of a source position.
 */
@Tag("unit")
class CompilerDefectTest {

    @BeforeAll
    static void init() {
        Instruction.init();
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
