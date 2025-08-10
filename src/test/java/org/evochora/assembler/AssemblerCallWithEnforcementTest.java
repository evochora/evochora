package org.evochora.assembler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AssemblerCallWithEnforcementTest {

    private AnnotatedLine L(String s, int n) { return new AnnotatedLine(s, n, "test.s"); }

    @Test
    void calling_registerAbi_proc_without_with_is_rejected() {
        List<AnnotatedLine> src = new ArrayList<>();
        // Register-ABI proc (WITH one formal)
        src.add(L(".PROC LIB.REGABI.WORK WITH X", 1));
        src.add(L(".EXPORT LIB.REGABI.WORK", 2));
        src.add(L("RET", 3));
        src.add(L(".ENDP", 4));

        // Import alias and attempt to call without .WITH
        src.add(L(".IMPORT LIB.REGABI.WORK AS W", 10));
        src.add(L(".ORG 0|0", 11));
        src.add(L("CALL W", 12)); // should require .WITH

        Assembler asm = new Assembler();
        AssemblerException ex = assertThrows(AssemblerException.class, () -> asm.assemble(src, "CallWithoutWith_Enforcement", false));
        assertTrue(ex.getMessage().toLowerCase().contains("required"), "Error should indicate that .WITH is required for register-ABI procs");
    }
}
