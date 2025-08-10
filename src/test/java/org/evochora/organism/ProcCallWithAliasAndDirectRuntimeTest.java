package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AnnotatedLine;
import org.evochora.assembler.Assembler;
import org.evochora.assembler.ProgramMetadata;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime validation that CALL ... .WITH works for both direct kernel labels and import aliases
 * and that copy-in/out semantics update caller registers.
 */
public class ProcCallWithAliasAndDirectRuntimeTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    private AnnotatedLine L(String s, int n) { return new AnnotatedLine(s, n, "rt2.s"); }

    private World worldFromLayout(ProgramMetadata md) {
        Config.WORLD_SHAPE[0] = Math.max(Config.WORLD_SHAPE[0], 64);
        Config.WORLD_SHAPE[1] = Math.max(Config.WORLD_SHAPE[1], 8);
        World w = new World(Config.WORLD_SHAPE, true);
        for (Map.Entry<int[], Integer> e : md.machineCodeLayout().entrySet()) {
            int[] c = e.getKey();
            int x = c.length > 0 ? c[0] : 0;
            int y = c.length > 1 ? c[1] : 0;
            w.setSymbol(Symbol.fromInt(e.getValue()), x, y);
        }
        for (Map.Entry<int[], Symbol> e : md.initialWorldObjects().entrySet()) {
            int[] c = e.getKey();
            int x = c.length > 0 ? c[0] : 0;
            int y = c.length > 1 ? c[1] : 0;
            w.setSymbol(e.getValue(), x, y);
        }
        return w;
    }

    @Test
    void direct_kernel_call_runtime() {
        // CHILD kernel declared WITH A; increments A
        // TOP directly calls kernel: CALL LIB.CHILD.INC .WITH %DR1
        List<AnnotatedLine> src = new ArrayList<>();
        // Place kernel away from entry
        src.add(L(".ORG 10|0", 0));
        src.add(L(".DIR 1|0", 0));
        src.add(L(".PROC LIB.CHILD.INC WITH A", 1));
        src.add(L(".EXPORT LIB.CHILD.INC", 2));
        src.add(L("ADDI A DATA:1", 3));
        src.add(L("RET", 4));
        src.add(L(".ENDP", 5));

        src.add(L(".ORG 0|0", 10));
        src.add(L(".DIR 1|0", 11));
        src.add(L(".REG %A1 1", 12));
        src.add(L("CALL LIB.CHILD.INC .WITH %A1", 13));
        src.add(L(".REG %F0 0", 14));               // Alias for formal DR0
        src.add(L("SETR %A1 %F0", 15));             // Explicit copy-back using alias

        Assembler asm = new Assembler();
        ProgramMetadata md = asm.assemble(src, "RT_DirectKernel", false);

        World w = worldFromLayout(md);
        Simulation sim = new Simulation(w);
        Organism org = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());

        // Initialize DR1 = DATA:9
        assertTrue(org.setDr(1, new Symbol(Config.TYPE_DATA, 9).toInt()));

        // Run enough ticks to execute adapter + child + copy-back
        for (int i = 0; i < 200; i++) sim.tick();

        Object dr1 = org.getDr(1);
        assertNotNull(dr1);
        Symbol s1 = Symbol.fromInt((Integer) dr1);
        assertEquals(Config.TYPE_DATA, s1.type());
        assertEquals(10, s1.toScalarValue(), "DR1 should be incremented by child via copy-back");
    }

    @Test
    void alias_call_runtime() {
        // CHILD kernel declared WITH A; increments A
        // TOP imports alias CHD and calls CALL CHD .WITH %DR2
        List<AnnotatedLine> src = new ArrayList<>();
        // Place kernel away from entry
        src.add(L(".ORG 10|0", 0));
        src.add(L(".PROC LIB.CHILD.INC WITH A", 1));
        src.add(L(".EXPORT LIB.CHILD.INC", 2));
        src.add(L("ADDI A DATA:1", 3));
        src.add(L("RET", 4));
        src.add(L(".ENDP", 5));

        src.add(L(".IMPORT LIB.CHILD.INC AS CHD", 10));
        src.add(L(".ORG 0|0", 20));
        src.add(L(".DIR 1|0", 21));
        src.add(L(".REG %A2 2", 22));
        src.add(L("CALL CHD .WITH %A2", 23));

        Assembler asm = new Assembler();
        ProgramMetadata md = asm.assemble(src, "RT_AliasKernel", false);

        World w = worldFromLayout(md);
        Simulation sim = new Simulation(w);
        Organism org = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());

        // Initialize DR2 = DATA:3
        assertTrue(org.setDr(2, new Symbol(Config.TYPE_DATA, 3).toInt()));

        // Run ticks
        for (int i = 0; i < 40; i++) sim.tick();

        Object dr2 = org.getDr(2);
        assertNotNull(dr2);
        Symbol s2 = Symbol.fromInt((Integer) dr2);
        assertEquals(Config.TYPE_DATA, s2.type());
        assertEquals(4, s2.toScalarValue(), "DR2 should be incremented by child via copy-back (alias call)");
    }
}
