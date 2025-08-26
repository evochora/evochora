package org.evochora.compiler.backend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class EmissionCallerMarshallingTest {

	private static SourceInfo src(String f, int l) { return new SourceInfo(f, l, ""); }

	@Test
	@Tag("unit")
	void insertsCallerPushPopAroundCall() {
		// core:call_with { actuals: ["%DR1", "%DR2"] } followed by CALL
        Map<String, IrValue> args = new HashMap<>();
        args.put("actuals", new IrValue.ListVal(List.of(new IrValue.Str("%DR1"), new IrValue.Str("%DR2"))));
        IrDirective callWith = new IrDirective("core", "call_with", args, src("main.s", 1));

        IrInstruction call = new IrInstruction("CALL", List.of(new IrVec(new int[]{1,0})), src("main.s", 2));
        List<IrItem> items = List.of(callWith, call);

        EmissionRegistry reg = EmissionRegistry.initializeWithDefaults();
        LinkingContext ctx = new LinkingContext();
        List<IrItem> out = items;
        for (IEmissionRule r : reg.rules()) out = r.apply(out, ctx);

        // Expect: PUSH %DR1, PUSH %DR2, CALL, POP %DR2, POP %DR1
        assertThat(out).hasSize(5);
        assertThat(((IrInstruction) out.get(0)).opcode()).isEqualTo("PUSH");
        assertThat(((IrReg) ((IrInstruction) out.get(0)).operands().get(0)).name()).isEqualTo("%DR1");
        assertThat(((IrInstruction) out.get(1)).opcode()).isEqualTo("PUSH");
        assertThat(((IrReg) ((IrInstruction) out.get(1)).operands().get(0)).name()).isEqualTo("%DR2");
        assertThat(((IrInstruction) out.get(2)).opcode()).isEqualTo("CALL");
        assertThat(((IrInstruction) out.get(3)).opcode()).isEqualTo("POP");
        assertThat(((IrReg) ((IrInstruction) out.get(3)).operands().get(0)).name()).isEqualTo("%DR2");
        assertThat(((IrInstruction) out.get(4)).opcode()).isEqualTo("POP");
        assertThat(((IrReg) ((IrInstruction) out.get(4)).operands().get(0)).name()).isEqualTo("%DR1");
    }
}



