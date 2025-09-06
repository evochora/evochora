package org.evochora.compiler.backend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.emit.features.ProcedureMarshallingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Emission: Procedure Marshalling Rules")
public class EmissionProcedureMarshallingTest {

    private static SourceInfo src(String file, int line) {
        return new SourceInfo(file, line, 0);
    }

    private List<IrItem> runFullEmission(List<IrItem> items) {
        EmissionRegistry reg = EmissionRegistry.initializeWithDefaults();
        LinkingContext ctx = new LinkingContext();
        List<IrItem> out = items;
        for (IEmissionRule r : reg.rules()) {
            out = r.apply(out, ctx);
        }
        return out;
    }

    @Test
    @Tag("unit")
    @DisplayName("Should marshall standard REF/VAL procedure correctly")
    void marshallingAndSourceInfoForRefAndValProcedure() {
        Map<String, IrValue> enterArgs = new HashMap<>();
        enterArgs.put("name", new IrValue.Str("myProc"));
        enterArgs.put("refParams", new IrValue.ListVal(List.of(new IrValue.Str("%rA"))));
        enterArgs.put("valParams", new IrValue.ListVal(List.of(new IrValue.Str("v1"))));
        IrDirective procEnter = new IrDirective("core", "proc_enter", enterArgs, src("test.s", 2));

        List<IrItem> body = List.of(
            new IrInstruction("NOP", List.of(), src("test.s", 3)),
            new IrInstruction("RET", List.of(), src("test.s", 4))
        );

        IrDirective procExit = new IrDirective("core", "proc_exit", new HashMap<>(), src("test.s", 5));

        List<IrItem> items = new java.util.ArrayList<>();
        items.add(procEnter);
        items.addAll(body);
        items.add(procExit);

        ProcedureMarshallingRule rule = new ProcedureMarshallingRule();
        List<IrItem> rewritten = rule.apply(items, new LinkingContext());

        List<IrInstruction> instructions = rewritten.stream().filter(i -> i instanceof IrInstruction).map(i -> (IrInstruction) i).collect(Collectors.toList());

        assertThat(instructions).hasSize(5);
        // Assert Logic: POP, POP, NOP, PUSH, RET
        assertThat(instructions.get(0).opcode()).isEqualTo("POP");
        assertThat(((IrReg) instructions.get(0).operands().get(0)).name()).isEqualTo("%FPR0");
        assertThat(instructions.get(1).opcode()).isEqualTo("POP");
        assertThat(((IrReg) instructions.get(1).operands().get(0)).name()).isEqualTo("%FPR1");
        assertThat(instructions.get(2).opcode()).isEqualTo("NOP");
        assertThat(instructions.get(3).opcode()).isEqualTo("PUSH");
        assertThat(((IrReg) instructions.get(3).operands().get(0)).name()).isEqualTo("%FPR0");
        assertThat(instructions.get(4).opcode()).isEqualTo("RET");

        // Assert SourceInfo
        assertThat(instructions.get(0).source().lineNumber()).isEqualTo(2); // POP
        assertThat(instructions.get(1).source().lineNumber()).isEqualTo(2); // POP
        assertThat(instructions.get(2).source().lineNumber()).isEqualTo(3); // NOP
        assertThat(instructions.get(3).source().lineNumber()).isEqualTo(4); // PUSH
        assertThat(instructions.get(4).source().lineNumber()).isEqualTo(4); // RET
    }

    @Test
    @Tag("unit")
    @DisplayName("Should handle legacy `arity` procedure correctly")
    void backwardCompatibilityTest() {
        Map<String, IrValue> enterArgs = new HashMap<>();
        enterArgs.put("name", new IrValue.Str("old"));
        enterArgs.put("arity", new IrValue.Int64(1));
        IrDirective procEnter = new IrDirective("core", "proc_enter", enterArgs, src("test.s", 2));

        List<IrItem> body = List.of(
            new IrInstruction("NOP", List.of(), src("test.s", 3)),
            new IrInstruction("RET", List.of(), src("test.s", 4))
        );

        IrDirective procExit = new IrDirective("core", "proc_exit", new HashMap<>(), src("test.s", 5));

        List<IrItem> items = new java.util.ArrayList<>();
        items.add(procEnter);
        items.addAll(body);
        items.add(procExit);

        ProcedureMarshallingRule rule = new ProcedureMarshallingRule();
        List<IrItem> rewritten = rule.apply(items, new LinkingContext());

        List<IrInstruction> instructions = rewritten.stream().filter(i -> i instanceof IrInstruction).map(i -> (IrInstruction) i).collect(Collectors.toList());

        assertThat(instructions).hasSize(4);
        assertThat(instructions.get(0).opcode()).isEqualTo("POP");
        assertThat(instructions.get(1).opcode()).isEqualTo("NOP");
        assertThat(instructions.get(2).opcode()).isEqualTo("PUSH");
        assertThat(instructions.get(3).opcode()).isEqualTo("RET");
    }

    @Test
    @Tag("unit")
    @DisplayName("Should transform conditional RET in REF/VAL procedure")
    void shouldTransformConditionalRet() {
        // .PROC myProc REF %rA VAL %rB
        //     IFR %rB
        //     RET
        // .ENDP
        Map<String, IrValue> enterArgs = new HashMap<>();
        enterArgs.put("name", new IrValue.Str("myProc"));
        enterArgs.put("refParams", new IrValue.ListVal(List.of(new IrValue.Str("%rA"))));
        enterArgs.put("valParams", new IrValue.ListVal(List.of(new IrValue.Str("%rB"))));
        IrDirective procEnter = new IrDirective("core", "proc_enter", enterArgs, src("test.s", 1));

        IrReg rB = new IrReg("%rB");
        IrInstruction ifr = new IrInstruction("IFR", List.of(rB), src("test.s", 2));
        IrInstruction ret = new IrInstruction("RET", List.of(), src("test.s", 3));

        IrDirective procExit = new IrDirective("core", "proc_exit", new HashMap<>(), src("test.s", 4));

        List<IrItem> items = new java.util.ArrayList<>();
        items.add(procEnter);
        items.add(ifr);
        items.add(ret);
        items.add(procExit);

        // We test the whole pipeline because the rule interacts with others.
        List<IrItem> rewritten = runFullEmission(items);

        // Filter for easier assertions
        List<IrInstruction> instructions = rewritten.stream()
            .filter(i -> i instanceof IrInstruction)
            .map(i -> (IrInstruction) i)
            .collect(Collectors.toList());
        List<IrLabelDef> labels = rewritten.stream()
            .filter(i -> i instanceof IrLabelDef)
            .map(i -> (IrLabelDef) i)
            .collect(Collectors.toList());

        // Expect: POP %FPR0, POP %FPR1, INR %rB, JMPI _safe_ret_X, PUSH %FPR0, RET, _safe_ret_X:
        assertThat(instructions).hasSize(6);
        assertThat(labels).hasSize(1);

        // Prologue
        assertThat(instructions.get(0).opcode()).isEqualTo("POP"); // %FPR0 for %rA
        assertThat(instructions.get(1).opcode()).isEqualTo("POP"); // %FPR1 for %rB

        // Conditional RET logic
        IrInstruction negated = instructions.get(2);
        assertThat(negated.opcode()).isEqualTo("INR");
        assertThat(negated.operands()).containsExactly(rB);

        IrInstruction jmpi = instructions.get(3);
        assertThat(jmpi.opcode()).isEqualTo("JMPI");
        assertThat(jmpi.operands().get(0)).isInstanceOf(IrLabelRef.class);
        String labelName = ((IrLabelRef) jmpi.operands().get(0)).labelName();
        assertThat(labelName).startsWith("_safe_ret_");

        // Epilogue
        assertThat(instructions.get(4).opcode()).isEqualTo("PUSH"); // PUSH %FPR0 for ref param %rA
        assertThat(((IrReg) instructions.get(4).operands().get(0)).name()).isEqualTo("%FPR0");
        assertThat(instructions.get(5).opcode()).isEqualTo("RET");

        // Label
        assertThat(labels.get(0).name()).isEqualTo(labelName);
    }
}
