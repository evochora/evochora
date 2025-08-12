// In einer neuen Datei, z.B. ErrorTest.java
package org.evochora.app.setup.prototypes;

import org.evochora.compiler.internal.legacy.AssemblyProgram;

public class Dummy extends AssemblyProgram {

    @Override
    public String getProgramCode() {
        return """
               """;
    }
}