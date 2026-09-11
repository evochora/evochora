package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.evochora.datapipeline.api.resources.database.dto.InstructionView;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests how an instruction's recorded argument cells are turned into operands.
 * <p>
 * The cells of an instruction are read by a length and described by a signature, and both are
 * derived from one list of operand sources when the instruction is registered. A record with
 * fewer cells than the signature describes can therefore not come from an organism whose code was
 * overwritten — such cells are recorded with other contents, not with fewer entries. It would mean
 * those two derivations disagree, and that is a defect rather than something to display.
 */
@Tag("unit")
class OrganismStateConverterArgumentCellsTest {

    private static int setiId;

    @BeforeAll
    static void registerInstructionSet() {
        Instruction.init();
        setiId = Instruction.getInstructionIdByName("SETI");
    }

    /** SETI takes a register and a literal, so a complete record holds two cells. */
    private static InstructionView resolve(List<Integer> cells) {
        return OrganismStateConverter.resolveInstructionView(
                setiId, cells, 0, 0, new int[]{1, 2}, new int[]{1, 0},
                false, null, List.of(), new int[]{100, 100}, null);
    }

    @Test
    void resolvesEveryOperandOfACompleteRecord() {
        InstructionView view = resolve(List.of(
                new Molecule(Config.TYPE_REGISTER, 0).toInt(),
                new Molecule(Config.TYPE_DATA, 5).toInt()));

        assertThat(view.opcodeName).isEqualTo("SETI");
        assertThat(view.arguments).hasSize(2);
        assertThat(view.argumentTypes).containsExactly("REGISTER", "IMMEDIATE");
    }

    /**
     * An overwritten operand cell still is a cell: it arrives with whatever now stands in it, and
     * is resolved like any other. Nothing here fails, because nothing here is broken.
     */
    @Test
    void resolvesAnOperandCellThatHoldsSomethingElseNow() {
        InstructionView view = resolve(List.of(
                new Molecule(Config.TYPE_REGISTER, 0).toInt(),
                new Molecule(Config.TYPE_ENERGY, 42).toInt()));

        assertThat(view.arguments).hasSize(2);
        assertThat(view.arguments.get(1).moleculeType).isEqualTo("ENERGY");
        assertThat(view.arguments.get(1).value).isEqualTo(42);
    }

    /** A record that ends before the signature does is a defect and says so. */
    @Test
    void failsOnARecordShorterThanTheSignature() {
        assertThatThrownBy(() -> resolve(List.of(new Molecule(Config.TYPE_REGISTER, 0).toInt())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SETI")
                .hasMessageContaining("declares 2 operands");
    }
}
