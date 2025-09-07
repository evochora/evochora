package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.CompilerTestBase;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Disabled;


import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class NewRefParameterTokenMapTest extends CompilerTestBase {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    @Disabled("Not sure if REF params need to be the TokenMap, need to investigate!")
    void testRefParameterIsInTokenMap() throws Exception {
        String source = String.join("\n",
                ".PROC MY_PROC REF PARAM1",
                "NOP",
                ".ENDP"
        );
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "ref_param_test.s", envProps);
        System.out.println(source);
        boolean foundParam = artifact.tokenMap().values().stream()
                .anyMatch(tokenInfo -> "PARAM1".equals(tokenInfo.tokenText()));

        assertTrue(foundParam, "REF parameter 'PARAM1' should be in TokenMap");
    }
}