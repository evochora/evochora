package org.evochora.compiler.backend.link;

import java.util.Arrays;
import java.util.Map;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a call with parameters produces a binding, and that its key names the cell
 * holding that call.
 * <p>
 * The binding is collected from the call's REF/VAL operand lists, which only exist on the call's
 * own IR type. Anything that rebuilds the instruction as a plain one during linking drops those
 * lists, and the binding is then never collected - silently, because the result still compiles
 * and still runs.
 */
public class CallSiteBindingAddressTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    @Tag("unit")
    void bindingKeyNamesTheCallWithoutAPrecedingLabel() throws Exception {
        assertBindingKeysPointAtCalls(compile(String.join("\n",
                "SETI %DR0 DATA:29",
                "CALL INC REF %DR0",
                "WAIT",
                ".ORG 0|1",
                "EXPORT .PROC INC REF A",
                "  ADDI A DATA:1",
                "  RET",
                ".ENDP")));
    }

    private static ProgramArtifact compile(String source) throws Exception {
        return new Compiler().compile(
                Arrays.asList(source.split("\\r?\\n")), "callsite.s",
                new EnvironmentProperties(new int[]{64, 64}, true));
    }

    private static void assertBindingKeysPointAtCalls(ProgramArtifact artifact) {
        int callOpcode = Instruction.getInstructionIdByName("CALL");
        assertThat(artifact.callSiteBindings())
                .as("the program has a call site with bindings")
                .hasSize(1);

        for (Map.Entry<Integer, Map<Integer, Integer>> binding : artifact.callSiteBindings().entrySet()) {
            int linearAddress = binding.getKey();
            int[] coord = artifact.linearAddressToCoord().get(linearAddress);
            assertThat(coord)
                    .as("linear address %d of a call site binding is a known address", linearAddress)
                    .isNotNull();

            Integer moleculeValue = machineCodeAt(artifact, coord);
            assertThat(moleculeValue)
                    .as("linear address %d points at an emitted cell", linearAddress)
                    .isNotNull();
            assertThat(Molecule.fromInt(moleculeValue).toScalarValue())
                    .as("linear address %d holds the CALL its bindings belong to", linearAddress)
                    .isEqualTo(callOpcode);
        }
    }

    /**
     * Reads the emitted molecule at a relative coordinate; the layout is keyed by array instances,
     * so the lookup compares contents.
     */
    private static Integer machineCodeAt(ProgramArtifact artifact, int[] coord) {
        for (Map.Entry<int[], Integer> entry : artifact.machineCodeLayout().entrySet()) {
            if (Arrays.equals(entry.getKey(), coord)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
