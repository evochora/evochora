package org.evochora.assembler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CallWithDirectKernelBindingTest {

    private AnnotatedLine L(String s, int n) { return new AnnotatedLine(s, n, "test.s"); }

    @Test
    void call_with_on_direct_kernel_label_is_accepted() {
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".PROC LIB.CHILD.WITH1 WITH A", 1));
        src.add(L(".EXPORT LIB.CHILD.WITH1", 2));
        src.add(L("RET", 3));
        src.add(L(".ENDP", 4));

        src.add(L(".ORG 0|0", 10));
        src.add(L(".REG %ARG 0", 11));
        src.add(L("CALL LIB.CHILD.WITH1 .WITH %ARG", 12));

        Assembler asm = new Assembler();
        assertDoesNotThrow(() -> asm.assemble(src, "CallDirectKernel_With", false));
    }
}
