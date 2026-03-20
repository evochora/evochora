package org.evochora.compiler.backend.link.features;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.features.proc.CallSiteBindingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.features.proc.IrCallInstruction;
import org.evochora.compiler.model.ir.IrImm;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrReg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CallSiteBindingRule}.
 * Verifies that CALL instructions have their parameter register bindings
 * correctly collected into the linking context.
 */
@Tag("unit")
class CallSiteBindingRuleTest {

    private CallSiteBindingRule rule;
    private LinkingContext context;
    private LayoutResult layout;
    private SourceInfo dummySource;

    @BeforeEach
    void setUp() {
        IInstructionSet stubIsa = new StubInstructionSet();
        rule = new CallSiteBindingRule();
        context = new LinkingContext(null, stubIsa);
        layout = new LayoutResult(
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap()
        );
        dummySource = new SourceInfo("test.s", 1, 0);
    }

    @Test
    void collectsRefOperandBindings() {
        IrInstruction call = new IrCallInstruction(
                "CALL",
                List.of(new IrImm(0)),
                List.of(new IrReg("%DR0"), new IrReg("%DR1")),
                List.of(),
                dummySource
        );

        IrInstruction result = rule.apply(call, context, layout);

        assertThat(result).isSameAs(call);
        assertThat(context.callSiteBindings()).containsKey(0);
        assertThat(context.callSiteBindings().get(0)).containsExactly(0, 1);
    }

    @Test
    void collectsValOperandBindings() {
        IrInstruction call = new IrCallInstruction(
                "CALL",
                List.of(new IrImm(0)),
                List.of(),
                List.of(new IrReg("%PR1")),
                dummySource
        );

        rule.apply(call, context, layout);

        assertThat(context.callSiteBindings().get(0)).containsExactly(1001);
    }

    @Test
    void collectsMixedRefAndValBindings() {
        IrInstruction call = new IrCallInstruction(
                "CALL",
                List.of(new IrImm(0)),
                List.of(new IrReg("%DR2")),
                List.of(new IrReg("%PR0")),
                dummySource
        );

        rule.apply(call, context, layout);

        assertThat(context.callSiteBindings().get(0)).containsExactly(2, 1000);
    }

    @Test
    void doesNotCreateBindingForCallWithoutRegisterOperands() {
        IrInstruction call = new IrCallInstruction(
                "CALL",
                List.of(new IrImm(0)),
                List.of(),
                List.of(),
                dummySource
        );

        rule.apply(call, context, layout);

        assertThat(context.callSiteBindings()).isEmpty();
    }

    @Test
    void passesNonCallInstructionThrough() {
        IrInstruction addi = new IrInstruction(
                "ADDI",
                List.of(new IrImm(42)),
                dummySource
        );

        IrInstruction result = rule.apply(addi, context, layout);

        assertThat(result).isSameAs(addi);
        assertThat(context.callSiteBindings()).isEmpty();
    }

    @Test
    void bindsAtCurrentAddress() {
        context.nextAddress(); // advance to address 1
        context.nextAddress(); // advance to address 2

        IrInstruction call = new IrCallInstruction(
                "CALL",
                List.of(new IrImm(0)),
                List.of(new IrReg("%DR0")),
                List.of(),
                dummySource
        );

        rule.apply(call, context, layout);

        assertThat(context.callSiteBindings()).containsKey(2);
        assertThat(context.callSiteBindings()).doesNotContainKey(0);
    }

    @Test
    void throwsOnUnresolvableRegister() {
        IrInstruction call = new IrCallInstruction(
                "CALL",
                List.of(new IrImm(0)),
                List.of(new IrReg("%UNKNOWN")),
                List.of(),
                dummySource
        );

        assertThatThrownBy(() -> rule.apply(call, context, layout))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("%UNKNOWN");
    }

    /**
     * Minimal ISA stub that resolves register tokens to IDs
     * following the standard register bank layout.
     */
    private static class StubInstructionSet implements IInstructionSet {
        private static final int PR_BASE = 1000;

        @Override
        public Optional<Integer> resolveRegisterToken(String token) {
            String upper = token.toUpperCase().replace("%", "");
            if (upper.startsWith("DR")) {
                return parseIndex(upper, "DR", 0);
            } else if (upper.startsWith("PR")) {
                return parseIndex(upper, "PR", PR_BASE);
            }
            return Optional.empty();
        }

        private Optional<Integer> parseIndex(String token, String prefix, int base) {
            try {
                return Optional.of(base + Integer.parseInt(token.substring(prefix.length())));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        @Override
        public Optional<Integer> getInstructionIdByName(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<Signature> getSignatureById(int id) {
            return Optional.empty();
        }
    }
}
