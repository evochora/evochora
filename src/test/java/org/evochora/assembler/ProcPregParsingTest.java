package org.evochora.assembler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ProcPregParsingTest {

    private AnnotatedLine L(String s, int n) { return new AnnotatedLine(s, n, "test.s"); }

    @Test
    void proc_with_formals_and_preg_are_captured_and_autoRet_is_appended() {
        List<AnnotatedLine> src = new ArrayList<>();
        src.add(L(".PROC LIB.DEMO.WORK WITH X Y", 1));
        src.add(L(".EXPORT LIB.DEMO.WORK", 2));
        src.add(L(".PREG %TMP0 0", 3));
        src.add(L("SETR X Y", 4));
        // no RET here on purpose
        src.add(L(".ENDP", 5));

        DefinitionExtractor ex = new DefinitionExtractor("prog");
        List<AnnotatedLine> main = ex.extractFrom(src);

        // Main code should contain label + body + implicit RET
        String concatenated = String.join("\n", main.stream().map(AnnotatedLine::content).toList());
        assertTrue(concatenated.contains("LIB.DEMO.WORK:"), "Kernel label missing");
        assertTrue(concatenated.contains("SETR X Y"), "Body content missing");
        assertTrue(concatenated.contains("RET"), "Implicit RET should be appended at .ENDP");

        Map<String, DefinitionExtractor.ProcMeta> meta = ex.getProcMetaMap();
        DefinitionExtractor.ProcMeta pm = meta.get("LIB.DEMO.WORK");
        assertNotNull(pm, "Proc meta missing for LIB.DEMO.WORK");
        assertEquals(List.of("X", "Y"), pm.formalParams(), "Formals not captured correctly");
        assertEquals(1, pm.pregAliases().size());
        assertEquals(0, pm.pregAliases().get("%TMP0"));
    }

    @Test
    void preg_invalid_context_and_syntax_raise_errors() {
        // .PREG outside .PROC
        List<AnnotatedLine> bad1 = List.of(L(".PREG %TMP 0", 1));
        DefinitionExtractor ex1 = new DefinitionExtractor("prog");
        AssemblerException e1 = assertThrows(AssemblerException.class, () -> ex1.extractFrom(bad1));
        assertTrue(e1.getMessage().contains(".PREG"), "Should complain about invalid .PREG context");

        // .PREG with invalid index
        List<AnnotatedLine> bad2 = new ArrayList<>();
        bad2.add(L(".PROC LIB.BAD WITH X", 1));
        bad2.add(L(".PREG %TMP 2", 2));
        bad2.add(L(".ENDP", 3));
        DefinitionExtractor ex2 = new DefinitionExtractor("prog");
        AssemblerException e2 = assertThrows(AssemblerException.class, () -> ex2.extractFrom(bad2));
        assertTrue(e2.getMessage().toLowerCase().contains("preg"), "Should complain about invalid .PREG index");

        // .PROC WITH %X (invalid)
        List<AnnotatedLine> bad3 = new ArrayList<>();
        bad3.add(L(".PROC LIB.BAD WITH %X", 1));
        bad3.add(L(".ENDP", 2));
        DefinitionExtractor ex3 = new DefinitionExtractor("prog");
        AssemblerException e3 = assertThrows(AssemblerException.class, () -> ex3.extractFrom(bad3));
        assertTrue(e3.getMessage().toLowerCase().contains("formal"), "Should reject formals starting with %");
    }
}
