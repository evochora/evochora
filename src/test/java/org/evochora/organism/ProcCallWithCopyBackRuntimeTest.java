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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end runtime tests for CALL ... .WITH copy-in/out semantics.
 * - DR actuals: callee modifies formals; caller DRs reflect changes after return.
 * - PR actuals: callee modifies formals; caller PRs reflect changes after return.
 */
public class ProcCallWithCopyBackRuntimeTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    private AnnotatedLine L(String s, int n) {
        return new AnnotatedLine(s, n, "rt.s");
    }

    private World worldFromLayout(ProgramMetadata md) {
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
    void dr_actual_copyBack_runtime() {
        // CHILD: register-ABI with A; increments A
        // PARENT: register-ABI with X; calls CHILD .WITH X
        // TOP: CALL PARENT .WITH %DR3; After return, %DR3 should be incremented
        List<AnnotatedLine> src = new ArrayList<>();
        // Place kernels away from entry
        src.add(L(".ORG 10|0", 0));
        src.add(L(".PROC LIB.CHILD.INC WITH A", 1));
        src.add(L(".EXPORT LIB.CHILD.INC", 2));
        src.add(L("ADDI A DATA:1", 3));
        src.add(L("RET", 4));
        src.add(L(".ENDP", 5));

        src.add(L(".PROC LIB.PARENT.WORK WITH X", 10));
        src.add(L(".EXPORT LIB.PARENT.WORK", 11));
        src.add(L("CALL LIB.CHILD.INC .WITH X", 12));
        src.add(L("RET", 13));
        src.add(L(".ENDP", 14));

        src.add(L(".IMPORT LIB.CHILD.INC AS CHD", 20));
        src.add(L(".IMPORT LIB.PARENT.WORK AS PRN", 21));
        src.add(L(".ORG 0|0", 30));
        src.add(L(".DIR 1|0", 31));
        // Define an alias for argument register DR3 and use it
        src.add(L(".REG %ARG 3", 32));
        src.add(L("CALL PRN .WITH %ARG", 33));

        Assembler asm = new Assembler();
        ProgramMetadata md = asm.assemble(src, "RT_DR_Actual", false);

        World w = worldFromLayout(md);
        Simulation sim = new Simulation(w);
        Organism org = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());

        // Init DR3 = DATA:5
        assertTrue(org.setDr(3, new Symbol(Config.TYPE_DATA, 5).toInt()));

        // Run enough ticks to execute copy-in, CALL, child ADDI/RET, copy-back
        for (int i = 0; i < 200; i++) sim.tick();

        Object dr3 = org.getDr(3);
        assertNotNull(dr3, "DR3 should be set");
        assertTrue(dr3 instanceof Integer);
        Symbol s = Symbol.fromInt((Integer) dr3);
        assertEquals(Config.TYPE_DATA, s.type());
        assertEquals(6, s.toScalarValue(), "DR3 should be incremented by child via copy-back");
    }

    @Test
    void pr_actual_copyBack_runtime() {
        // CHILD: register-ABI with A; increments A
        // PARENT: register-ABI with X; .PREG %P0 0; set %P0 := X; CALL CHILD .WITH %P0; SETR %DR5 %P0; RET
        // TOP: CALL PARENT .WITH %DR2; After return, %DR5 should be X+1 (copied from updated PR0)
        List<AnnotatedLine> src = new ArrayList<>();
        // Place kernels away from entry
        src.add(L(".ORG 10|0", 0));
        src.add(L(".PROC LIB.CHILD.INC WITH A", 1));
        src.add(L(".EXPORT LIB.CHILD.INC", 2));
        src.add(L("ADDI A DATA:1", 3));
        src.add(L("RET", 4));
        src.add(L(".ENDP", 5));

        src.add(L(".PROC LIB.PARENT.WORK WITH X", 10));
        src.add(L(".EXPORT LIB.PARENT.WORK", 11));
        src.add(L(".PREG %P0 0", 12));
        src.add(L("SETR %P0 X", 13));                  // PR0 := X
        src.add(L("CALL LIB.CHILD.INC .WITH %P0", 14));// %P0 passed by reference
        // Define alias for output register DR5 and use it
        src.add(L(".REG %OUT 5", 15));
        src.add(L("SETR %OUT %P0", 16));               // %OUT := updated PR0
        src.add(L("RET", 17));
        src.add(L(".ENDP", 17));

        src.add(L(".IMPORT LIB.CHILD.INC AS CHD", 20));
        src.add(L(".IMPORT LIB.PARENT.WORK AS PRN", 21));
        src.add(L(".ORG 0|0", 30));
        src.add(L(".DIR 1|0", 31));
        // Define alias for input register DR2 and use it
        src.add(L(".REG %IN 2", 32));
        src.add(L("CALL PRN .WITH %IN", 33));

        Assembler asm = new Assembler();
        ProgramMetadata md = asm.assemble(src, "RT_PR_Actual", false);

        World w = worldFromLayout(md);
        Simulation sim = new Simulation(w);
        Organism org = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());

        // Init DR2 = DATA:1
        assertTrue(org.setDr(2, new Symbol(Config.TYPE_DATA, 1).toInt()));

        // Run enough ticks
        for (int i = 0; i < 200; i++) sim.tick();

        Object dr5 = org.getDr(5);
        assertNotNull(dr5, "DR5 should be set by parent after child returns");
        assertTrue(dr5 instanceof Integer);
        Symbol s5 = Symbol.fromInt((Integer) dr5);
        assertEquals(Config.TYPE_DATA, s5.type());
        assertEquals(2, s5.toScalarValue(), "DR5 should hold X+1 copied back from PR0 after child returns");
    }
}
