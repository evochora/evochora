package org.evochora.compiler.backend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for IFP/INP conditional instruction marshalling with CALL/RET.
 * These tests verify that the marshalling system correctly handles the new
 * passability-based conditional instructions.
 */
@Tag("unit")
class EmissionIfpMarshallingTest {

    private static SourceInfo src(String file, int line) {
        return new SourceInfo(file, line, 0);
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

    @Test
    @DisplayName("Should transform conditional CALL with IFPR")
    void shouldTransformConditionalCallWithIfpr() {
        // IR for: IFPR %VEC_REG, CALL myProc REF %rA
        IrReg vecReg = new IrReg("%VEC_REG");
        IrReg rA = new IrReg("%rA");
        IrInstruction ifpr = new IrInstruction("IFPR", List.of(vecReg), src("main.s", 1));
        IrLabelRef target = new IrLabelRef("myProc");
        IrInstruction call = new IrInstruction("CALL", List.of(target), List.of(rA), Collections.emptyList(), src("main.s", 2));

        List<IrItem> out = runEmission(List.of(ifpr, call));

        // Expect: INPR %VEC_REG, JMPI _safe_call_X, PUSH %rA, CALL myProc, POP %rA, _safe_call_X:
        assertThat(out).hasSize(6);
        assertThat(out.get(0)).isInstanceOf(IrInstruction.class);
        IrInstruction negated = (IrInstruction) out.get(0);
        assertThat(negated.opcode()).isEqualTo("INPR");
        assertThat(negated.operands()).containsExactly(vecReg);

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

    @Test
    @DisplayName("Should transform conditional CALL with IFPI")
    void shouldTransformConditionalCallWithIfpi() {
        // IR for: IFPI 1|0, CALL myProc REF %rA
        IrReg rA = new IrReg("%rA");
        IrInstruction ifpi = new IrInstruction("IFPI", List.of(new IrReg("1|0")), src("main.s", 1));
        IrLabelRef target = new IrLabelRef("myProc");
        IrInstruction call = new IrInstruction("CALL", List.of(target), List.of(rA), Collections.emptyList(), src("main.s", 2));

        List<IrItem> out = runEmission(List.of(ifpi, call));

        // Expect: INPI 1|0, JMPI _safe_call_X, PUSH %rA, CALL myProc, POP %rA, _safe_call_X:
        assertThat(out).hasSize(6);
        assertThat(out.get(0)).isInstanceOf(IrInstruction.class);
        IrInstruction negated = (IrInstruction) out.get(0);
        assertThat(negated.opcode()).isEqualTo("INPI");
        assertThat(negated.operands()).containsExactly(new IrReg("1|0"));

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

    @Test
    @DisplayName("Should transform conditional CALL with IFPS")
    void shouldTransformConditionalCallWithIfps() {
        // IR for: IFPS, CALL myProc REF %rA
        IrReg rA = new IrReg("%rA");
        IrInstruction ifps = new IrInstruction("IFPS", Collections.emptyList(), src("main.s", 1));
        IrLabelRef target = new IrLabelRef("myProc");
        IrInstruction call = new IrInstruction("CALL", List.of(target), List.of(rA), Collections.emptyList(), src("main.s", 2));

        List<IrItem> out = runEmission(List.of(ifps, call));

        // Expect: INPS, JMPI _safe_call_X, PUSH %rA, CALL myProc, POP %rA, _safe_call_X:
        assertThat(out).hasSize(6);
        assertThat(out.get(0)).isInstanceOf(IrInstruction.class);
        IrInstruction negated = (IrInstruction) out.get(0);
        assertThat(negated.opcode()).isEqualTo("INPS");
        assertThat(negated.operands()).isEmpty();

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
