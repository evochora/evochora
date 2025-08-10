package org.evochora.assembler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AssemblerCallWithEnforcementDirectTest {

    private AnnotatedLine L(String s, int n) { return new AnnotatedLine(s, n, "test.s"); }

    @Test
    void calling_registerAbi_proc_by_kernel_label_without_with_is_rejected() {
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".PROC LIB.REGABI.WORK WITH X", 1));
        src.add(L(".EXPORT LIB.REGABI.WORK", 2));
        src.add(L("RET", 3));
        src.add(L(".ENDP", 4));

        src.add(L(".ORG 0|0", 10));
        src.add(L("CALL LIB.REGABI.WORK", 11)); // should require .WITH

        Assembler asm = new Assembler();
        AssemblerException ex = assertThrows(AssemblerException.class, () -> asm.assemble(src, "CallDirectKernel_WithoutWith", false));
        assertTrue(ex.getMessage().toLowerCase().contains("requires"), "Error should indicate that .WITH is required for register-ABI procs");
    }
}
