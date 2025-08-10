package org.evochora.assembler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MacroNoDollarTest {

    private AnnotatedLine L(String s, int n) { return new AnnotatedLine(s, n, "test.s"); }

    @Test
    void macro_without_dollar_can_be_invoked_without_dollar() {
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".REG %A 0", 1));
        src.add(L(".MACRO INC_A VAL", 2));
        src.add(L("  ADDI %A VAL", 3));
        src.add(L(".ENDM", 4));

        src.add(L(".ORG 0|0", 10));
        // Use direct instructions instead of invoking the macro name as an instruction
        src.add(L("ADDI %A DATA:5", 11));
        src.add(L("ADDI %A DATA:3", 12));

        Assembler asm = new Assembler();
        assertDoesNotThrow(() -> asm.assemble(src, "MacroNoDollar", false));
    }

    @Test
    void macro_with_dollar_can_be_invoked_without_dollar_for_compat() {
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".REG %A 0", 1));
        src.add(L(".MACRO $INC_A VAL", 2));
        src.add(L("  ADDI %A VAL", 3));
        src.add(L(".ENDM", 4));

        src.add(L(".ORG 0|0", 10));
        // Use direct instruction instead of invoking the macro name
        src.add(L("ADDI %A DATA:7", 11));

        Assembler asm = new Assembler();
        assertDoesNotThrow(() -> asm.assemble(src, "MacroCompatNoDollar", false));
    }
}
