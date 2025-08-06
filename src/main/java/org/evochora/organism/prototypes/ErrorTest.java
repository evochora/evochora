// In einer neuen Datei, z.B. ErrorTest.java
package org.evochora.organism.prototypes;

import org.evochora.assembler.AssemblyProgram;

public class ErrorTest extends AssemblyProgram {

    @Override
    public String getAssemblyCode() {
        return """

#.REG %TEST 1
#.REG %VEC 2
#SETV %VEC 1|0
#SETI %TEST DATA:1
#POKE %TEST %VEC
#SETI %TEST DATA:1



.REG %REGTEST 0

.MACRO $TEST %DATA
SETI %REGTEST %DATA
PUSH %REGTEST
#$TEST2 %DATA
.ENDM

.MACRO $TEST2 %DATA2
SETI %REGTEST %DATA2
PUSH %REGTEST
$TEST %DATA2
.ENDM

#$TEST DATA:1
$TEST2 DATA:2

#SETI %REGTEST DATA:1

                """;
    }
}