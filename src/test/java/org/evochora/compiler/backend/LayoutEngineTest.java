package org.evochora.compiler.backend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.LayoutEngine;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.ir.*;
import org.evochora.compiler.ir.placement.IrVectorPlacement;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.Test;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.junit.jupiter.api.Tag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
public class LayoutEngineTest {

    private static SourceInfo src(String file, int line) {
        return new SourceInfo(file, line, 0);
    }

    @Test
    void laysOutOrgDirPlaceAndInstructions() throws Exception {
        Instruction.init();
        Map<String, IrValue> orgArgs = new HashMap<>();
        orgArgs.put("position", new IrValue.Vector(new int[]{2, 3}));
        IrDirective org = new IrDirective("core", "org", orgArgs, src("main.s", 1));

        Map<String, IrValue> dirArgs = new HashMap<>();
        dirArgs.put("direction", new IrValue.Vector(new int[]{1, 0}));
        IrDirective dir = new IrDirective("core", "dir", dirArgs, src("main.s", 2));

        IrLabelDef label = new IrLabelDef("L", src("main.s", 3));
        IrInstruction seti = new IrInstruction("SETI", List.of(new IrReg("%DR0"), new IrTypedImm("DATA", 1)), src("main.s", 4));
        IrDirective push = new IrDirective("core", "push_ctx", new HashMap<>(), src("lib.inc", 9));

        Map<String, IrValue> placeArgs = new HashMap<>();
        placeArgs.put("type", new IrValue.Str("ENERGY"));
        placeArgs.put("value", new IrValue.Int64(50));
        IrVectorPlacement placement = new IrVectorPlacement(List.of(5, 0));
        placeArgs.put("placements", new IrValue.PlacementListVal(List.of(placement)));
        IrDirective place = new IrDirective("core", "place", placeArgs, src("lib.inc", 10));

        IrProgram ir = new IrProgram("Test", List.of(org, dir, label, seti, push, place));

        LayoutEngine engine = new LayoutEngine();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{10, 10}, true);
        LayoutResult res = engine.layout(ir, new RuntimeInstructionSetAdapter(), envProps);

        assertThat(res.linearAddressToCoord().get(0)).containsExactly(2, 3);
        assertThat(res.linearAddressToCoord().get(1)).containsExactly(3, 3);
        assertThat(res.linearAddressToCoord().get(2)).containsExactly(4, 3);
        assertThat(res.labelToAddress().get("L")).isEqualTo(0);

        // Note: Labels now occupy 1 cell in the grid (like Tierra/Avida templates).
        // This shifts all subsequent positions by 1, so the ENERGY placement
        // at relative offset [5,0] is now at [11,3] instead of [10,3].
        boolean foundPlace = res.initialWorldObjects().keySet().stream()
                .anyMatch(c -> java.util.Arrays.equals(c, new int[]{11, 3}));
        assertThat(foundPlace).isTrue();
    }
}
