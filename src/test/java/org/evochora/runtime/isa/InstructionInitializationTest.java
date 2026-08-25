package org.evochora.runtime.isa;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the instruction set is registered exactly once.
 * <p>
 * The array registries the virtual machine reads on every instruction are static fields that
 * {@code buildArrayRegistries} replaces wholesale: it assigns a new empty array and fills it
 * afterwards. Repeating that while a simulation runs would expose the running threads to an array
 * that is momentarily empty, so an instruction would appear unregistered although it is not.
 * <p>
 * Registration therefore has to happen once, before any simulation thread starts, and every later
 * call has to leave the registries alone. Comparing array identity across a second call is the
 * direct way to see that: a rebuild would produce different array objects.
 */
@Tag("unit")
class InstructionInitializationTest {

    /** What registration builds once: the registries the virtual machine reads, and the
     * description of the instruction set handed to callers. */
    private static final List<String> ARRAY_REGISTRY_FIELDS = List.of(
            "PLANNERS_ARRAY",
            "OPERAND_SOURCES_ARRAY",
            "INSTRUCTION_LENGTHS_BASE",
            "INSTRUCTION_LENGTHS_DIMS_MULTIPLIER",
            "PARALLEL_EXECUTE_SAFE",
            "NAMES_ARRAY",
            "SIGNATURES_ARRAY",
            // Built alongside them and handed out to callers, so it has to be the same object too.
            "INSTRUCTION_INFO_CACHE");

    @Test
    void repeatedInit_LeavesTheArrayRegistriesUntouched() throws Exception {
        Instruction.init();
        List<Object> afterFirst = currentRegistries();

        Instruction.init();

        List<Object> afterSecond = currentRegistries();
        for (int i = 0; i < ARRAY_REGISTRY_FIELDS.size(); i++) {
            // Compared by identity: a rebuild fills the new arrays with the same content, so only
            // the array object itself shows that the registries were replaced.
            assertThat(afterSecond.get(i))
                    .as("%s must not be rebuilt", ARRAY_REGISTRY_FIELDS.get(i))
                    .isSameAs(afterFirst.get(i));
        }
    }

    /**
     * Registration produces the description of the instruction set along with everything else.
     * <p>
     * Built on its own, next to registration rather than as part of it, it would be built by whoever
     * asks for it first — and by two callers at once when two ask at once. Both would then walk the
     * class hierarchy of every instruction to arrive at the same answer, and the field handing that
     * answer out would be written outside the lock that guards the rest.
     */
    @Test
    void init_ProducesTheInstructionSetDescriptionAsWell() throws Exception {
        Instruction.init();

        // Cleared first: another test in this JVM may have asked for the description already, which
        // would fill the field whether or not registration does it.
        Field cache = Instruction.class.getDeclaredField("INSTRUCTION_INFO_CACHE");
        cache.setAccessible(true);
        Field initialized = Instruction.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        cache.set(null, null);
        initialized.set(null, false);

        Instruction.init();

        assertThat((java.util.List<?>) cache.get(null))
                .as("the description of the instruction set, built by registration")
                .isNotNull()
                // Not merely present: a caller sizes an array to the highest opcode it names, and an
                // empty description would size that array for no instruction at all.
                .isNotEmpty();
    }

    /** The current array objects, to be compared by identity rather than by content. */
    private static List<Object> currentRegistries() throws Exception {
        List<Object> registries = new ArrayList<>();
        for (String name : ARRAY_REGISTRY_FIELDS) {
            Field field = Instruction.class.getDeclaredField(name);
            field.setAccessible(true);
            registries.add(field.get(null));
        }
        return registries;
    }
}
