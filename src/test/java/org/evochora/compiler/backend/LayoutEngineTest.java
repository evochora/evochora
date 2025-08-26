package org.evochora.compiler.backend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.LayoutEngine;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.ir.*;
import org.junit.jupiter.api.Test;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.junit.jupiter.api.Tag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class LayoutEngineTest {

	private static SourceInfo src(String file, int line) {
		return new SourceInfo(file, line, "");
	}

	@Test
	@Tag("unit")
	void laysOutOrgDirPlaceAndInstructions() {
		// Build a small IR program manually
		Map<String, IrValue> orgArgs = new HashMap<>();
		orgArgs.put("position", new IrValue.Vector(new int[]{2, 3}));
		IrDirective org = new IrDirective("core", "org", orgArgs, src("main.s", 1));

		Map<String, IrValue> dirArgs = new HashMap<>();
		dirArgs.put("direction", new IrValue.Vector(new int[]{1, 0}));
		IrDirective dir = new IrDirective("core", "dir", dirArgs, src("main.s", 2));

		IrLabelDef label = new IrLabelDef("L", src("main.s", 3));

		IrInstruction seti = new IrInstruction("SETI", List.of(new IrReg("%DR0"), new IrTypedImm("DATA", 1)), src("main.s", 4));

		Map<String, IrValue> placeArgs = new HashMap<>();
		placeArgs.put("type", new IrValue.Str("ENERGY"));
		placeArgs.put("value", new IrValue.Int64(50));
		placeArgs.put("position", new IrValue.Vector(new int[]{5, 0}));
		IrDirective place = new IrDirective("core", "place", placeArgs, src("lib.inc", 10));

		IrProgram ir = new IrProgram("Test", List.of(org, dir, label, seti, place));

        LayoutEngine engine = new LayoutEngine();
        LayoutResult res = engine.layout(ir, new RuntimeInstructionSetAdapter(), 2);

		// Start at 2|3, direction 1|0:
		// seti opcode at 2|3, two operands at 3|3 and 4|3
		assertThat(res.linearAddressToCoord().get(0)).containsExactly(2, 3);
		assertThat(res.linearAddressToCoord().get(1)).containsExactly(3, 3);
		assertThat(res.linearAddressToCoord().get(2)).containsExactly(4, 3);

		// label L before SETI should point to address 0
		assertThat(res.labelToAddress().get("L")).isEqualTo(0);

		// place at basePos(=currentPos at include enter) + 5|0 => 7|3
		boolean foundPlace = res.initialWorldObjects().keySet().stream().anyMatch(c -> c[0] == 7 && c[1] == 3);
		assertThat(foundPlace).isTrue();
	}
}


