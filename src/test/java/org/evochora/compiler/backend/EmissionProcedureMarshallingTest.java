package org.evochora.compiler.backend;

import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;
import org.evochora.compiler.api.SourceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class EmissionProcedureMarshallingTest {

    private static SourceInfo src(String file, int line) { return new SourceInfo(file, line, ""); }

	@Test
	@Tag("unit")
	void insertsPrologAndEpilogBasedOnArity() {
		Map<String, IrValue> enter = new HashMap<>();
		enter.put("name", new IrValue.Str("INC"));
		enter.put("arity", new IrValue.Int64(1));
		IrDirective procEnter = new IrDirective("core", "proc_enter", enter, src("m.s", 1));

		IrInstruction body = new IrInstruction("ADDI", List.of(new IrReg("%FPR0"), new IrTypedImm("DATA", 1)), src("m.s", 2));

		Map<String, IrValue> exit = new HashMap<>();
		exit.put("name", new IrValue.Str("INC"));
		exit.put("arity", new IrValue.Int64(1));
		IrDirective procExit = new IrDirective("core", "proc_exit", exit, src("m.s", 3));

		List<IrItem> items = List.of(procEnter, body, procExit);
		EmissionRegistry reg = EmissionRegistry.initializeWithDefaults();
		LinkingContext ctx = new LinkingContext();

		List<IrItem> rewritten = items;
		for (IEmissionRule rule : reg.rules()) rewritten = rule.apply(rewritten, ctx);

		// Expected sequence: proc_enter, POP %FPR0, body, PUSH %FPR0, proc_exit
		assertThat(rewritten).hasSize(5);
		assertThat(((IrDirective) rewritten.get(0)).name()).isEqualTo("proc_enter");
		assertThat(((IrInstruction) rewritten.get(1)).opcode()).isEqualTo("POP");
		assertThat(((IrInstruction) rewritten.get(1)).operands().get(0)).isInstanceOf(IrReg.class);
		assertThat(((IrReg) ((IrInstruction) rewritten.get(1)).operands().get(0)).name()).isEqualTo("%FPR0");
		assertThat(((IrInstruction) rewritten.get(2)).opcode()).isEqualTo("ADDI");
		assertThat(((IrInstruction) rewritten.get(3)).opcode()).isEqualTo("PUSH");
		assertThat(((IrReg) ((IrInstruction) rewritten.get(3)).operands().get(0)).name()).isEqualTo("%FPR0");
		assertThat(((IrDirective) rewritten.get(4)).name()).isEqualTo("proc_exit");
	}
}


