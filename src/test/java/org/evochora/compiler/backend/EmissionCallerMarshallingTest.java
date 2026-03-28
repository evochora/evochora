package org.evochora.compiler.backend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.features.proc.CallerMarshallingRule;
import org.evochora.compiler.features.proc.IrCallInstruction;
import org.evochora.compiler.model.ir.*;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the emission rules for caller marshalling. These tests ensure
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
        EmissionRegistry reg = new EmissionRegistry();
        reg.register(new org.evochora.compiler.features.proc.ProcedureMarshallingRule());
        reg.register(new CallerMarshallingRule());
        List<IrItem> out = items;
        for (IEmissionRule r : reg.rules()) {
            out = r.apply(out);
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
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(rB), List.of(imm123), List.of(), List.of(), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSI 123, PUSH %rB, CALL myProc, POP %rB
            assertThat(out).hasSize(4);
            assertThat(out.get(0)).isEqualTo(IrInstruction.synthetic("PUSI", List.of(imm123), call.source()));
            assertThat(out.get(1)).isEqualTo(IrInstruction.synthetic("PUSH", List.of(rB), call.source()));
            assertThat(out.get(2)).isEqualTo(call);
            assertThat(out.get(3)).isEqualTo(IrInstruction.synthetic("POP", List.of(rB), call.source()));
        }

        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with multiple REF operands")
        void marshallsCallWithMultipleRefOperands() {
            // IR for: CALL p REF %rX, %rY
            IrReg rX = new IrReg("%rX");
            IrReg rY = new IrReg("%rY");
            IrLabelRef target = new IrLabelRef("p");
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(rX, rY), Collections.emptyList(), List.of(), List.of(), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSH %rY, PUSH %rX, CALL p, POP %rY, POP %rX (correct post-call cleanup order: LIFO)
            assertThat(out).hasSize(5);
            assertThat(out.get(0)).isEqualTo(IrInstruction.synthetic("PUSH", List.of(rY), call.source()));
            assertThat(out.get(1)).isEqualTo(IrInstruction.synthetic("PUSH", List.of(rX), call.source()));
            assertThat(out.get(2)).isEqualTo(call);
            assertThat(out.get(3)).isEqualTo(IrInstruction.synthetic("POP", List.of(rY), call.source()));
            assertThat(out.get(4)).isEqualTo(IrInstruction.synthetic("POP", List.of(rX), call.source()));
        }

        @Test
        @Tag("unit")
        @DisplayName("Should not marshall a plain CALL instruction")
        void doesNotMarshallPlainCall() {
            // IR for: CALL someLabel
            IrInstruction call = new IrCallInstruction("CALL", List.of(new IrLabelRef("someLabel")), List.of(), List.of(), List.of(), List.of(), src("main.s", 1));

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
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), Collections.emptyList(), List.of(labelParam), List.of(), List.of(), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSV myLabel, CALL myProc
            assertThat(out).hasSize(2);
            assertThat(out.get(0)).isEqualTo(IrInstruction.synthetic("PUSV", List.of(labelParam), call.source()));
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
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(rA), List.of(imm123, labelParam), List.of(), List.of(), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSV myLabel, PUSI 123, PUSH %rA, CALL myProc, POP %rA
            assertThat(out).hasSize(5);
            assertThat(out.get(0)).isEqualTo(IrInstruction.synthetic("PUSV", List.of(labelParam), call.source()));
            assertThat(out.get(1)).isEqualTo(IrInstruction.synthetic("PUSI", List.of(imm123), call.source()));
            assertThat(out.get(2)).isEqualTo(IrInstruction.synthetic("PUSH", List.of(rA), call.source()));
            assertThat(out.get(3)).isEqualTo(call);
            assertThat(out.get(4)).isEqualTo(IrInstruction.synthetic("POP", List.of(rA), call.source()));
        }

        @Test
        @Tag("unit")
        @DisplayName("Should transform conditional CALL with REF operand")
        void shouldTransformConditionalCallWithRef() {
            // IR for: IFR %rA, CALL myProc REF %rA
            IrReg rA = new IrReg("%rA");
            IrInstruction ifr = new IrInstruction("IFR", List.of(rA), src("main.s", 1));
            IrLabelRef target = new IrLabelRef("myProc");
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(rA), Collections.emptyList(), List.of(), List.of(), src("main.s", 2));

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

            assertThat(out.get(2)).isEqualTo(IrInstruction.synthetic("PUSH", List.of(rA), call.source()));
            assertThat(out.get(3)).isEqualTo(call);
            assertThat(out.get(4)).isEqualTo(IrInstruction.synthetic("POP", List.of(rA), call.source()));

            assertThat(out.get(5)).isInstanceOf(IrLabelDef.class);
            IrLabelDef labelDef = (IrLabelDef) out.get(5);
            assertThat(labelDef.name()).isEqualTo(labelName);
        }

        @Test
        @Tag("unit")
        @DisplayName("Should transform conditional CALL with zero-operand IFER")
        void shouldTransformConditionalCallWithIfer() {
            // IR for: IFER, CALL myProc REF %rA
            IrReg rA = new IrReg("%rA");
            IrInstruction ifer = new IrInstruction("IFER", Collections.emptyList(), src("main.s", 1));
            IrLabelRef target = new IrLabelRef("myProc");
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(rA), Collections.emptyList(), List.of(), List.of(), src("main.s", 2));

            List<IrItem> out = runEmission(List.of(ifer, call));

            // Expect: INER (negated, no operands), JMPI _safe_call_X, PUSH %rA, CALL myProc, POP %rA, _safe_call_X:
            assertThat(out).hasSize(6);
            assertThat(out.get(0)).isInstanceOf(IrInstruction.class);
            IrInstruction negated = (IrInstruction) out.get(0);
            assertThat(negated.opcode()).isEqualTo("INER");
            assertThat(negated.operands()).isEmpty();

            assertThat(out.get(1)).isInstanceOf(IrInstruction.class);
            IrInstruction jmpi = (IrInstruction) out.get(1);
            assertThat(jmpi.opcode()).isEqualTo("JMPI");
            assertThat(jmpi.operands().get(0)).isInstanceOf(IrLabelRef.class);
            String labelName = ((IrLabelRef) jmpi.operands().get(0)).labelName();
            assertThat(labelName).startsWith("_safe_call_");

            assertThat(out.get(2)).isEqualTo(IrInstruction.synthetic("PUSH", List.of(rA), call.source()));
            assertThat(out.get(3)).isEqualTo(call);
            assertThat(out.get(4)).isEqualTo(IrInstruction.synthetic("POP", List.of(rA), call.source()));

            assertThat(out.get(5)).isInstanceOf(IrLabelDef.class);
            IrLabelDef labelDef = (IrLabelDef) out.get(5);
            assertThat(labelDef.name()).isEqualTo(labelName);
        }
    }

    @Nested
    @DisplayName("LREF/LVAL Location Marshalling")
    class LrefLvalMarshalling {

        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with LREF operand (PUSL pre-call, POPL post-call)")
        void marshallsCallWithLref() {
            // IR for: CALL proc LREF %LR0
            IrReg lr0 = new IrReg("%LR0");
            IrLabelRef target = new IrLabelRef("proc");
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(), List.of(), List.of(lr0), List.of(), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSL %LR0, CALL proc, POPL %LR0
            assertThat(out).hasSize(3);
            assertThat(out.get(0)).isEqualTo(IrInstruction.synthetic("PUSL", List.of(lr0), call.source()));
            assertThat(out.get(1)).isEqualTo(call);
            assertThat(out.get(2)).isEqualTo(IrInstruction.synthetic("POPL", List.of(lr0), call.source()));
        }

        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with LVAL register operand (PUSL pre-call, no post-call)")
        void marshallsCallWithLvalRegister() {
            // IR for: CALL proc LVAL %LR1
            IrReg lr1 = new IrReg("%LR1");
            IrLabelRef target = new IrLabelRef("proc");
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(), List.of(), List.of(), List.of(lr1), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSL %LR1, CALL proc (no POPL — LVAL is by value, no write-back)
            assertThat(out).hasSize(2);
            assertThat(out.get(0)).isEqualTo(IrInstruction.synthetic("PUSL", List.of(lr1), call.source()));
            assertThat(out.get(1)).isEqualTo(call);
        }

        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with LVAL label operand using PSLI instead of PUSL")
        void marshallsCallWithLvalLabel() {
            // IR for: CALL proc LVAL MY_LABEL
            IrLabelRef target = new IrLabelRef("proc");
            IrLabelRef labelParam = new IrLabelRef("MY_LABEL");
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(), List.of(), List.of(), List.of(labelParam), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PSLI MY_LABEL, CALL proc (PSLI for labels, not PUSL)
            assertThat(out).hasSize(2);
            assertThat(out.get(0)).isEqualTo(IrInstruction.synthetic("PSLI", List.of(labelParam), call.source()));
            assertThat(out.get(1)).isEqualTo(call);
        }

        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with multiple LVAL and LREF in correct location-stack order")
        void marshallsCallWithMultipleLvalAndLref() {
            // IR for: CALL proc LREF %LR0 %LR1 LVAL %LR2
            IrReg lr0 = new IrReg("%LR0");
            IrReg lr1 = new IrReg("%LR1");
            IrReg lr2 = new IrReg("%LR2");
            IrLabelRef target = new IrLabelRef("proc");
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(), List.of(), List.of(lr0, lr1), List.of(lr2), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Pre-call order: LVAL (reverse) then LREF (reverse)
            // PUSL %LR2 (LVAL, only one), PUSL %LR1 (LREF reverse), PUSL %LR0 (LREF reverse)
            // Post-call: POPL LREF (reverse) for write-back
            // POPL %LR1, POPL %LR0
            assertThat(out).hasSize(6);
            assertThat(out.get(0)).isEqualTo(IrInstruction.synthetic("PUSL", List.of(lr2), call.source()));
            assertThat(out.get(1)).isEqualTo(IrInstruction.synthetic("PUSL", List.of(lr1), call.source()));
            assertThat(out.get(2)).isEqualTo(IrInstruction.synthetic("PUSL", List.of(lr0), call.source()));
            assertThat(out.get(3)).isEqualTo(call);
            assertThat(out.get(4)).isEqualTo(IrInstruction.synthetic("POPL", List.of(lr1), call.source()));
            assertThat(out.get(5)).isEqualTo(IrInstruction.synthetic("POPL", List.of(lr0), call.source()));
        }

        @Test
        @Tag("unit")
        @DisplayName("Should marshall CALL with mixed REF + LREF (data + location marshalling)")
        void marshallsCallWithMixedRefAndLref() {
            // IR for: CALL proc REF %DR0 LREF %LR0
            IrReg dr0 = new IrReg("%DR0");
            IrReg lr0 = new IrReg("%LR0");
            IrLabelRef target = new IrLabelRef("proc");
            IrInstruction call = new IrCallInstruction("CALL", List.of(target), List.of(dr0), List.of(), List.of(lr0), List.of(), src("main.s", 1));

            List<IrItem> out = runEmission(List.of(call));

            // Expect: PUSH %DR0, PUSL %LR0, CALL proc, POPL %LR0, POP %DR0
            // Pre-call order: data args first (VAL then REF in reverse), then location args (LVAL then LREF in reverse)
            // Post-call order: LREF popped first (location stack), then REF popped (data stack)
            assertThat(out).hasSize(5);
            assertThat(out.get(0)).isEqualTo(IrInstruction.synthetic("PUSH", List.of(dr0), call.source()));
            assertThat(out.get(1)).isEqualTo(IrInstruction.synthetic("PUSL", List.of(lr0), call.source()));
            assertThat(out.get(2)).isEqualTo(call);
            assertThat(out.get(3)).isEqualTo(IrInstruction.synthetic("POPL", List.of(lr0), call.source()));
            assertThat(out.get(4)).isEqualTo(IrInstruction.synthetic("POP", List.of(dr0), call.source()));
        }
    }

    @Test
    @Tag("unit")
    @DisplayName("SourceInfo is preserved for marshalling")
    void sourceInfoIsPreservedForMarshalling() {
        // Test the CallerMarshallingRule with a REF parameter CALL
        IrInstruction call = new IrCallInstruction("CALL", List.of(new IrVec(new int[]{1, 0})),
                List.of(new IrReg("%DR1")), List.of(), List.of(), List.of(), src("test.s", 5));
        IrInstruction nop = new IrInstruction("NOP", Collections.emptyList(), src("test.s", 6));

        List<IrItem> items = List.of(call, nop);

        // Apply only the CallerMarshallingRule
        CallerMarshallingRule rule = new CallerMarshallingRule();
        List<IrItem> emitted = rule.apply(items);

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

}
