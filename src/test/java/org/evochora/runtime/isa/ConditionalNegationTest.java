package org.evochora.runtime.isa;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.evochora.runtime.isa.instructions.ConditionalInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every conditional instruction is registered together with its negation, and the compiler
 * relies on that pairing when it inverts a condition. These tests make a missing or lopsided
 * pair fail here, at registration, rather than in the code the compiler generates.
 */
@Tag("unit")
class ConditionalNegationTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    private static List<Map.Entry<Integer, String>> conditionals() {
        return Instruction.getAllInstructions().entrySet().stream()
                .filter(e -> Instruction.getInstructionClassById(e.getKey()) == ConditionalInstruction.class)
                .toList();
    }

    @Test
    void everyConditionalHasARegisteredNegationWithTheSameSignature() {
        List<Map.Entry<Integer, String>> conditionals = conditionals();
        assertThat(conditionals).as("conditional instructions are registered").isNotEmpty();

        for (Map.Entry<Integer, String> conditional : conditionals) {
            String name = conditional.getValue();
            Optional<String> negation = ConditionalInstruction.negationOf(name);
            assertThat(negation).as("negation of %s", name).isPresent();

            Integer negationId = Instruction.getInstructionIdByName(negation.get());
            assertThat(negationId).as("negation %s of %s is registered", negation.get(), name).isNotNull();
            assertThat(Instruction.getInstructionClassById(negationId))
                    .as("negation %s of %s is a conditional", negation.get(), name)
                    .isEqualTo(ConditionalInstruction.class);
            assertThat(Instruction.getSignatureById(negationId))
                    .as("signature of %s equals that of %s", negation.get(), name)
                    .isEqualTo(Instruction.getSignatureById(conditional.getKey()));
        }
    }

    @Test
    void negationIsSymmetric() {
        for (Map.Entry<Integer, String> conditional : conditionals()) {
            String name = conditional.getValue();
            String negation = ConditionalInstruction.negationOf(name).orElseThrow();
            assertThat(ConditionalInstruction.negationOf(negation))
                    .as("negation of the negation of %s", name)
                    .contains(name.toUpperCase());
            assertThat(negation).as("%s is not its own negation", name).isNotEqualTo(name.toUpperCase());
        }
    }

    @Test
    void anInstructionThatIsNoConditionalHasNoNegation() {
        assertThat(ConditionalInstruction.negationOf("NOP")).isEmpty();
        assertThat(ConditionalInstruction.negationOf("JMPI")).isEmpty();
        assertThat(ConditionalInstruction.negationOf("NO_SUCH_OPCODE")).isEmpty();
    }
}
