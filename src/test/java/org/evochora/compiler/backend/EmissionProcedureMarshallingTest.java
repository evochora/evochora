package org.evochora.compiler.backend;

import org.evochora.compiler.backend.emit.features.ProcedureMarshallingRule;
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

    	private static SourceInfo src(String file, int line) { return new SourceInfo(file, line, 0); }

	@Test
	@Tag("unit")
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

		List<IrItem> instructions = rewritten.stream().filter(i -> i instanceof IrInstruction).toList();

		if (instructions.size() != 5) {
			throw new AssertionError("Expected 5 instructions, but got " + instructions.size());
		}

		// Assert Logic: POP, POP, NOP, PUSH, RET
		assertThat(((IrInstruction) instructions.get(0)).opcode()).isEqualTo("POP");
		assertThat(((IrInstruction) instructions.get(0)).operands().get(0).toString()).isEqualTo("%FPR0");
		assertThat(((IrInstruction) instructions.get(1)).opcode()).isEqualTo("POP");
		assertThat(((IrInstruction) instructions.get(1)).operands().get(0).toString()).isEqualTo("%FPR1");
		assertThat(((IrInstruction) instructions.get(2)).opcode()).isEqualTo("NOP");
		assertThat(((IrInstruction) instructions.get(3)).opcode()).isEqualTo("PUSH");
		assertThat(((IrInstruction) instructions.get(3)).operands().get(0).toString()).isEqualTo("%FPR0");
		assertThat(((IrInstruction) instructions.get(4)).opcode()).isEqualTo("RET");

		// Assert SourceInfo
		assertThat(((IrInstruction) instructions.get(0)).source().lineNumber()).isEqualTo(2); // POP
		assertThat(((IrInstruction) instructions.get(1)).source().lineNumber()).isEqualTo(2); // POP
		assertThat(((IrInstruction) instructions.get(2)).source().lineNumber()).isEqualTo(3); // NOP
		assertThat(((IrInstruction) instructions.get(3)).source().lineNumber()).isEqualTo(4); // PUSH
		assertThat(((IrInstruction) instructions.get(4)).source().lineNumber()).isEqualTo(4); // RET
	}

	@Test
	@Tag("unit")
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

		List<IrItem> instructions = rewritten.stream().filter(i -> i instanceof IrInstruction).toList();

		assertThat(instructions).hasSize(4);
		assertThat(((IrInstruction) instructions.get(0)).opcode()).isEqualTo("POP");
		assertThat(((IrInstruction) instructions.get(1)).opcode()).isEqualTo("NOP");
		assertThat(((IrInstruction) instructions.get(2)).opcode()).isEqualTo("PUSH");
		assertThat(((IrInstruction) instructions.get(3)).opcode()).isEqualTo("RET");
	}
}
