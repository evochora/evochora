package org.evochora.compiler.backend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.emit.features.CallerMarshallingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    @BeforeAll
    static void setUp() {
        Instruction.init();
    }

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

            // Expect: PUSI 123, PUSH %rB, CALL myProc, POP %rB
            assertThat(out).hasSize(4);
            assertThat(out.get(0)).isEqualTo(new IrInstruction("PUSI", List.of(imm123), call.source()));
            assertThat(out.get(1)).isEqualTo(new IrInstruction("PUSH", List.of(rB), call.source()));
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

            // Expect: PUSH %rY, PUSH %rX, CALL p, POP %rY, POP %rX (correct post-call cleanup order: LIFO)
            assertThat(out).hasSize(5);
            assertThat(out.get(0)).isEqualTo(new IrInstruction("PUSH", List.of(rY), call.source()));
            assertThat(out.get(1)).isEqualTo(new IrInstruction("PUSH", List.of(rX), call.source()));
            assertThat(out.get(2)).isEqualTo(call);
            assertThat(out.get(3)).isEqualTo(new IrInstruction("POP", List.of(rY), call.source()));
            assertThat(out.get(4)).isEqualTo(new IrInstruction("POP", List.of(rX), call.source()));
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

        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with label as VAL parameter using PUSV")
        void marshallsCallWithLabelAsValParameter() {
            // IR for: CALL myProc VAL myLabel
            IrLabelRef target = new IrLabelRef("myProc");
            IrLabelRef labelParam = new IrLabelRef("myLabel");
            IrInstruction call = new IrInstruction("CALL", List.of(target), Collections.emptyList(), List.of(labelParam), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSV myLabel, CALL myProc
            assertThat(out).hasSize(2);
            assertThat(out.get(0)).isEqualTo(new IrInstruction("PUSV", List.of(labelParam), call.source()));
            assertThat(out.get(1)).isEqualTo(call);
        }

        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with mixed REF, VAL immediate, and VAL label parameters")
        void marshallsCallWithMixedParameters() {
            // IR for: CALL myProc REF %rA VAL 123 VAL myLabel
            IrReg rA = new IrReg("%rA");
            IrImm imm123 = new IrImm(123);
            IrLabelRef target = new IrLabelRef("myProc");
            IrLabelRef labelParam = new IrLabelRef("myLabel");
            IrInstruction call = new IrInstruction("CALL", List.of(target), List.of(rA), List.of(imm123, labelParam), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSV myLabel, PUSI 123, PUSH %rA, CALL myProc, POP %rA
            assertThat(out).hasSize(5);
            assertThat(out.get(0)).isEqualTo(new IrInstruction("PUSV", List.of(labelParam), call.source()));
            assertThat(out.get(1)).isEqualTo(new IrInstruction("PUSI", List.of(imm123), call.source()));
            assertThat(out.get(2)).isEqualTo(new IrInstruction("PUSH", List.of(rA), call.source()));
            assertThat(out.get(3)).isEqualTo(call);
            assertThat(out.get(4)).isEqualTo(new IrInstruction("POP", List.of(rA), call.source()));
        }

        @Test
        @Tag("unit")
        @DisplayName("Should transform conditional CALL with REF operand")
        void shouldTransformConditionalCallWithRef() {
            // IR for: IFR %rA, CALL myProc REF %rA
            IrReg rA = new IrReg("%rA");
            IrInstruction ifr = new IrInstruction("IFR", List.of(rA), src("main.s", 1));
            IrLabelRef target = new IrLabelRef("myProc");
            IrInstruction call = new IrInstruction("CALL", List.of(target), List.of(rA), Collections.emptyList(), src("main.s", 2));

            List<IrItem> out = runEmission(List.of(ifr, call));

            // Expect: INR %rA, JMPI _safe_call_X, PUSH %rA, CALL myProc, POP %rA, _safe_call_X:
            assertThat(out).hasSize(6);
            assertThat(out.get(0)).isInstanceOf(IrInstruction.class);
            IrInstruction negated = (IrInstruction) out.get(0);
            assertThat(negated.opcode()).isEqualTo("INR");
            assertThat(negated.operands()).containsExactly(rA);

            assertThat(out.get(1)).isInstanceOf(IrInstruction.class);
            IrInstruction jmpi = (IrInstruction) out.get(1);
            assertThat(jmpi.opcode()).isEqualTo("JMPI");
            assertThat(jmpi.operands().get(0)).isInstanceOf(IrLabelRef.class);
            String labelName = ((IrLabelRef) jmpi.operands().get(0)).labelName();
            assertThat(labelName).startsWith("_safe_call_");

            assertThat(out.get(2)).isEqualTo(new IrInstruction("PUSH", List.of(rA), call.source()));
            assertThat(out.get(3)).isEqualTo(call);
            assertThat(out.get(4)).isEqualTo(new IrInstruction("POP", List.of(rA), call.source()));

            assertThat(out.get(5)).isInstanceOf(IrLabelDef.class);
            IrLabelDef labelDef = (IrLabelDef) out.get(5);
            assertThat(labelDef.name()).isEqualTo(labelName);
        }
    }

    @Test
    @Tag("unit")
    @DisplayName("SourceInfo is preserved for marshalling")
    void sourceInfoIsPreservedForMarshalling() {
        // Test the CallerMarshallingRule in isolation with a simple core:call_with directive
        IrDirective callWith = new IrDirective("core", "call_with", 
            Map.of("actuals", new IrValue.ListVal(List.of(new IrValue.Str("%DR1")))), 
            src("test.s", 5));
        IrInstruction call = new IrInstruction("CALL", List.of(new IrVec(new int[]{1, 0})), src("test.s", 5));
        IrInstruction nop = new IrInstruction("NOP", Collections.emptyList(), src("test.s", 6));
        
        List<IrItem> items = List.of(callWith, call, nop);

        // Apply only the CallerMarshallingRule
        CallerMarshallingRule rule = new CallerMarshallingRule();
        LinkingContext ctx = new LinkingContext();
        List<IrItem> emitted = rule.apply(items, ctx);

        // Find the CALL instruction and marshalled instructions in the emitted list
        IrInstruction callInstruction = null;
        IrInstruction nopInstruction = null;
        List<IrInstruction> marshalledInstructions = new ArrayList<>();
        
        for (IrItem item : emitted) {
            if (item instanceof IrInstruction ins) {
                if ("CALL".equals(ins.opcode())) {
                    callInstruction = ins;
                } else if ("NOP".equals(ins.opcode())) {
                    nopInstruction = ins;
                } else if ("PUSH".equals(ins.opcode()) || "POP".equals(ins.opcode()) || "PUSI".equals(ins.opcode())) {
                    marshalledInstructions.add(ins);
                }
            }
        }

        assertThat(callInstruction).isNotNull();
        assertThat(nopInstruction).isNotNull();
        assertThat(marshalledInstructions).isNotEmpty();

        // Assert that the line number of the CALL instruction's SourceInfo is correct (line 5)
        assertThat(callInstruction.source().lineNumber()).isEqualTo(5);

        // Assert that the line number of the NOP instruction's SourceInfo is correct (line 6)
        assertThat(nopInstruction.source().lineNumber()).isEqualTo(6);
        
        // Assert that marshalled instructions inherit the correct SourceInfo from the CALL instruction
        for (IrInstruction marshalled : marshalledInstructions) {
            assertThat(marshalled.source().lineNumber()).isEqualTo(5);
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
