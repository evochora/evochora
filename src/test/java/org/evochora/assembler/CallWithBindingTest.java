package org.evochora.assembler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CallWithBindingTest {

    private AnnotatedLine L(String s, int n) { return new AnnotatedLine(s, n, "test.s"); }

    @Test
    void call_with_ok_for_registerAbi_proc() {
        // PROC with formals X Y; IMPORT alias; CALL alias .WITH %DR0 %DR1
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".PROC LIB.DEMO.WORK WITH X Y", 1));
        src.add(L(".EXPORT LIB.DEMO.WORK", 2));
        src.add(L("RET", 3));
        src.add(L(".ENDP", 4));

        src.add(L(".IMPORT LIB.DEMO.WORK AS WORK", 10));
        src.add(L(".ORG 0|0", 11));
        src.add(L(".REG %R0 0", 12));
        src.add(L(".REG %R1 1", 13));
        src.add(L("CALL WORK .WITH %R0 %R1", 14));

        Assembler asm = new Assembler();
        assertDoesNotThrow(() -> asm.assemble(src, "CallWith_OK", false));
    }

    @Test
    void call_with_rejected_for_dsAbi_proc() {
        // PROC without WITH (DS-ABI); .WITH not allowed
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".PROC LIB.DEMO.SUM", 1));
        src.add(L(".EXPORT LIB.DEMO.SUM", 2));
        src.add(L("ADDS", 3));
        src.add(L("RET", 4));
        src.add(L(".ENDP", 5));

        src.add(L(".IMPORT LIB.DEMO.SUM AS SUM", 10));
        src.add(L(".ORG 0|0", 11));
        src.add(L(".REG %R0 0", 12));
        src.add(L(".REG %R1 1", 13));
        src.add(L("CALL SUM .WITH %R0 %R1", 14));

        Assembler asm = new Assembler();
        AssemblerException ex = assertThrows(AssemblerException.class, () -> asm.assemble(src, "CallWith_DSABI", false));
        assertTrue(ex.getMessage().toLowerCase().contains("with"), "Error should mention .WITH not allowed for DS-ABI");
    }

    @Test
    void call_with_arity_mismatch() {
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".PROC LIB.DEMO.X WITH A B C", 1));
        src.add(L(".EXPORT LIB.DEMO.X", 2));
        src.add(L("RET", 3));
        src.add(L(".ENDP", 4));

        src.add(L(".IMPORT LIB.DEMO.X AS XPROC", 10));
        src.add(L(".ORG 0|0", 11));
        src.add(L(".REG %R0 0", 12));
        src.add(L(".REG %R1 1", 13));
        src.add(L("CALL XPROC .WITH %R0 %R1", 14)); // only 2, expects 3

        Assembler asm = new Assembler();
        AssemblerException ex = assertThrows(AssemblerException.class, () -> asm.assemble(src, "CallWith_Arity", false));
        assertTrue(ex.getMessage().toLowerCase().contains("arity"), "Error should mention arity mismatch");
    }

    @Test
    void call_with_non_register_actual_is_rejected() {
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".PROC LIB.DEMO.Y WITH P", 1));
        src.add(L(".EXPORT LIB.DEMO.Y", 2));
        src.add(L("RET", 3));
        src.add(L(".ENDP", 4));

        src.add(L(".IMPORT LIB.DEMO.Y AS YPROC", 10));
        src.add(L(".ORG 0|0", 11));
        src.add(L("CALL YPROC .WITH DATA:5", 12)); // not a register

        Assembler asm = new Assembler();
        AssemblerException ex = assertThrows(AssemblerException.class, () -> asm.assemble(src, "CallWith_NonReg", false));
        assertTrue(ex.getMessage().toLowerCase().contains("register"), "Error should indicate non-register actual");
    }
}
