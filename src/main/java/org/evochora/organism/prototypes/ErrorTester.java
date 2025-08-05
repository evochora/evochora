// src/main/java/org/evochora/organism/prototypes/InstructionLengthCounter.java
package org.evochora.organism.prototypes;

import org.evochora.assembler.AssemblyProgram;

public class ErrorTester extends AssemblyProgram {

    @Override
    public String getAssemblyCode() {
        return """
                JUMP START
                START:
                NOP
                JUMP SUB
                SUB:
                NOP
                """;
    }
}
