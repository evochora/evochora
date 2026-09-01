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
 * Verifies that the keys of {@code callSiteBindings} name the cell that holds the CALL they
 * belong to.
 * <p>
 * The key is a linear address, and the artifact carries a second mapping from linear addresses to
 * coordinates. Following a key through that mapping has to arrive at a CALL opcode, which requires
 * both sides to count addresses the same way. A label makes any disagreement visible: it occupies
 * a cell in the emitted program, so a count that covers only instructions falls behind at the
 * first one.
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

    @Test
    @Tag("unit")
    void bindingKeyNamesTheCallWithAPrecedingLabel() throws Exception {
        assertBindingKeysPointAtCalls(compile(String.join("\n",
                "SETI %DR0 DATA:29",
                "LOOP:",
                "CALL INC REF %DR0",
                "WAIT",
                ".ORG 0|1",
                "EXPORT .PROC INC REF A",
                "  ADDI A DATA:1",
                "  RET",
                ".ENDP")));
    }

    @Test
    @Tag("unit")
    void bindingKeysNameTheirCallsAcrossLabelsVectorsAndSeveralCalls() throws Exception {
        // Several labels, a vector operand and two calls: every kind of item that occupies a
        // different number of cells stands between the calls, so an address counted along the way
        // would drift by a different amount at each of them.
        ProgramArtifact artifact = compile(String.join("\n",
                "SETI %DR0 DATA:1",
                "START:",
                "SETV %DR1 1|0",
                "MIDDLE:",
                "CALL INC REF %DR0",
                "AGAIN:",
                "SETV %DR2 0|1",
                "CALL INC REF %DR1",
                "WAIT",
                ".ORG 0|1",
                "EXPORT .PROC INC REF A",
                "  ADDI A DATA:1",
                "  RET",
                ".ENDP"));

        int callOpcode = Instruction.getInstructionIdByName("CALL");
        assertThat(artifact.callSiteBindings()).as("both calls carry bindings").hasSize(2);

        for (Map.Entry<Integer, Map<Integer, Integer>> binding : artifact.callSiteBindings().entrySet()) {
            int linearAddress = binding.getKey();
            int[] coord = artifact.linearAddressToCoord().get(linearAddress);
            assertThat(coord).as("address %d is known", linearAddress).isNotNull();
            assertThat(Molecule.fromInt(machineCodeAt(artifact, coord)).toScalarValue())
                    .as("address %d holds a CALL", linearAddress)
                    .isEqualTo(callOpcode);
        }
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
