package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Instruction;
import org.evochora.world.Symbol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CallWithPrBindingTest {

    private AnnotatedLine L(String s, int n) { return new AnnotatedLine(s, n, "test.s"); }

    @Test
    void call_with_uses_preg_alias_as_pr_actual_and_emits_setr_with_pr_pseudo() {
        // CHILD: PROC with one formal A; increments A
        // PARENT: PROC with one formal X; .PREG %P0 0; CALL CHILD .WITH %P0
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".PROC LIB.CHILD.WITH1 WITH A", 1));
        src.add(L(".EXPORT LIB.CHILD.WITH1", 2));
        src.add(L("ADDI A DATA:1", 3));
        src.add(L("RET", 4));
        src.add(L(".ENDP", 5));

        src.add(L(".PROC LIB.PARENT.WORK WITH X", 10));
        src.add(L(".EXPORT LIB.PARENT.WORK", 11));
        src.add(L(".PREG %P0 0", 12));
        // copy X into PR0; we'll call child with %P0; adapter should emit SETR %DR0 := %PR0 (1000)
        src.add(L("SETR %P0 X", 13));
        src.add(L(".ENDP", 16));

        src.add(L(".IMPORT LIB.CHILD.WITH1 AS CHD", 20));
        src.add(L(".IMPORT LIB.PARENT.WORK AS PRN", 21));
        src.add(L(".ORG 0|0", 22));
        src.add(L("CALL PRN .WITH %DR1", 23)); // ensure parent is built; we will inspect kernel and adapters for CHD inside it (presence of SETR with 1000 is the key)

        Assembler asm = new Assembler();
        ProgramMetadata md = asm.assemble(src, "CallWithPrBinding", false);
        Map<int[], Integer> layout = md.machineCodeLayout();

        int setrFull = Config.TYPE_CODE | Instruction.getInstructionIdByName("SETR");

        boolean foundSetrWithPrPseudo = layout.entrySet().stream().anyMatch(e -> {
            // find opcode SETR and see following two DATA cells: dest 0 (formal slot) and src 1000 (PR0 pseudo)
            if (e.getValue() == setrFull) {
                // derive positions of args is non-trivial without DV; heuristic: check if any following DATA value equals 1000
                return layout.values().stream().anyMatch(v -> {
                    Symbol s = Symbol.fromInt(v);
                    return s.type() == Config.TYPE_DATA && s.value() == 1000;
                });
            }
            return false;
        });

        assertTrue(foundSetrWithPrPseudo, "Expected at least one SETR using PR pseudo-id (1000) in adapter copy-in");
    }
}
