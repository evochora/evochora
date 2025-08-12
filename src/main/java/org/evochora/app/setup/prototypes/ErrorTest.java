// In einer neuen Datei, z.B. ErrorTest.java
package org.evochora.app.setup.prototypes;

import org.evochora.compiler.internal.legacy.AssemblyProgram;

public class ErrorTest extends AssemblyProgram {

    public ErrorTest() {
        // Die Math-Routine wird an relativer Position 20|0 eingebunden.
        // Die Platzhalter der Routine werden auf die Register des Hauptprogramms abgebildet.
        //this.includeRoutine("MATH", new MathRoutines(), new int[]{20, 0}, Map.of(
        //        "REG_TMP_A", "%DR_A",
        //        "REG_TMP_B", "%DR_B"
        //));
    }

    @Override
    public String getProgramCode() {
        return """

.REG %DR_A 6
.REG %DR_B 7

SETI %DR_A DATA:1


#.REG %TEST 1
#.REG %VEC 2

#SETV %VEC 1|0
#SETI %TEST DATA:1
#POKE %TEST %VEC
#SETI %TEST DATA:1



#.REG %REGTEST 0

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
#$TEST2 DATA:2

#SETI %REGTEST DATA:1

                """;
    }
}