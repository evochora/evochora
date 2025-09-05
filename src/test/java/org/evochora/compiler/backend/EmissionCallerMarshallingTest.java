package org.evochora.compiler.backend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the emission rules for caller marshalling, covering both the new REF/VAL
 * parameter passing and the legacy `core:call_with` directive. These tests ensure
 * that the compiler correctly generates PUSH, POP, PUSI, and DROP instructions
 * around a CALL instruction to manage the stack for procedure calls.
 * This is a unit test and does not require any external resources.
 */
@DisplayName("Emission: Caller Marshalling Rules")
public class EmissionCallerMarshallingTest {

    private static SourceInfo src(String f, int l) {
        return new SourceInfo(f, l, 0);
    }

    private List<IrItem> runEmission(List<IrItem> items) {
        EmissionRegistry reg = EmissionRegistry.initializeWithDefaults();
        LinkingContext ctx = new LinkingContext();
        List<IrItem> out = items;
        for (IEmissionRule r : reg.rules()) {
            out = r.apply(out, ctx);
        }
        return out;
    }

    @Nested
    @DisplayName("New REF/VAL Marshalling")
    class RefValMarshalling {
        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with REF and VAL operands")
        void marshallsCallWithRefAndVal() {
            // IR for: CALL myProc REF %rB VAL 123
            IrReg rB = new IrReg("%rB");
            IrImm imm123 = new IrImm(123);
            IrLabelRef target = new IrLabelRef("myProc");
            IrInstruction call = new IrInstruction("CALL", List.of(target), List.of(rB), List.of(imm123), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSH %rB, PUSI 123, CALL myProc, POP %rB
            assertThat(out).hasSize(4);
            assertThat(out.get(0)).isEqualTo(new IrInstruction("PUSH", List.of(rB), call.source()));
            assertThat(out.get(1)).isEqualTo(new IrInstruction("PUSI", List.of(imm123), call.source()));
            assertThat(out.get(2)).isEqualTo(call);
            assertThat(out.get(3)).isEqualTo(new IrInstruction("POP", List.of(rB), call.source()));
        }

        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with multiple REF operands")
        void marshallsCallWithMultipleRefOperands() {
            // IR for: CALL p REF %rX, %rY
            IrReg rX = new IrReg("%rX");
            IrReg rY = new IrReg("%rY");
            IrLabelRef target = new IrLabelRef("p");
            IrInstruction call = new IrInstruction("CALL", List.of(target), List.of(rX, rY), Collections.emptyList(), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSH %rY, PUSH %rX, CALL p, POP %rX, POP %rY
            assertThat(out).hasSize(5);
            assertThat(out.get(0)).isEqualTo(new IrInstruction("PUSH", List.of(rY), call.source()));
            assertThat(out.get(1)).isEqualTo(new IrInstruction("PUSH", List.of(rX), call.source()));
            assertThat(out.get(2)).isEqualTo(call);
            assertThat(out.get(3)).isEqualTo(new IrInstruction("POP", List.of(rX), call.source()));
            assertThat(out.get(4)).isEqualTo(new IrInstruction("POP", List.of(rY), call.source()));
        }

        @Test
        @Tag("unit")
        @DisplayName("Should not marshall a plain CALL instruction")
        void doesNotMarshallPlainCall() {
            // IR for: CALL someLabel
            IrInstruction call = new IrInstruction("CALL", List.of(new IrLabelRef("someLabel")), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            assertThat(out).hasSize(1).containsExactly(call);
        }
    }

    @Nested
    @DisplayName("Legacy `core:call_with` Marshalling")
    class LegacyMarshalling {
        @Test
        @Tag("unit")
        @DisplayName("Should insert PUSH/POP for `core:call_with` directive")
        void insertsCallerPushPopAroundCall() {
            // core:call_with { actuals: ["%DR1", "%DR2"] } followed by CALL
            Map<String, IrValue> args = new HashMap<>();
            args.put("actuals", new IrValue.ListVal(List.of(new IrValue.Str("%DR1"), new IrValue.Str("%DR2"))));
            IrDirective callWith = new IrDirective("core", "call_with", args, src("main.s", 1));

            IrInstruction call = new IrInstruction("CALL", List.of(new IrVec(new int[]{1, 0})), src("main.s", 2));
            List<IrItem> items = List.of(callWith, call);

            List<IrItem> out = runEmission(items);

            // Expect: PUSH %DR1, PUSH %DR2, CALL, POP %DR2, POP %DR1
            assertThat(out).hasSize(5);
            assertThat(((IrInstruction) out.get(0)).opcode()).isEqualTo("PUSH");
            assertThat(((IrReg) ((IrInstruction) out.get(0)).operands().get(0)).name()).isEqualTo("%DR1");
            assertThat(((IrInstruction) out.get(1)).opcode()).isEqualTo("PUSH");
            assertThat(((IrReg) ((IrInstruction) out.get(1)).operands().get(0)).name()).isEqualTo("%DR2");
            assertThat(out.get(2)).isSameAs(call); // Check that the original call instruction is preserved
            assertThat(((IrInstruction) out.get(3)).opcode()).isEqualTo("POP");
            assertThat(((IrReg) ((IrInstruction) out.get(3)).operands().get(0)).name()).isEqualTo("%DR2");
            assertThat(((IrInstruction) out.get(4)).opcode()).isEqualTo("POP");
            assertThat(((IrReg) ((IrInstruction) out.get(4)).operands().get(0)).name()).isEqualTo("%DR1");
        }
    }
}
