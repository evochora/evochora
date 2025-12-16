package org.evochora.runtime.spi.thermodynamics;

import java.util.List;
import java.util.Optional;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

/**
 * Contains all runtime information required by a thermodynamic policy
 * to calculate energy and entropy changes.
 *
 * @param instruction The instruction being executed.
 * @param organism The organism executing the instruction.
 * @param environment The current environment.
 * @param resolvedOperands The list of operands, resolved at runtime.
 * @param targetInfo Optional information about the instruction's target cell.
 */
public record ThermodynamicContext(
    Instruction instruction,
    Organism organism,
    Environment environment,
    List<Instruction.Operand> resolvedOperands,
    Optional<TargetInfo> targetInfo
) {
    /**
     * Contains information about an instruction's target cell, if applicable
     * (e.g., for PEEK, POKE, SCAN).
     *
     * @param coordinate The absolute coordinate of the target cell.
     * @param molecule The molecule present in the target cell.
     * @param ownerId The ID of the organism that owns the target cell (0 if unowned).
     */
    public record TargetInfo(
        int[] coordinate,
        Molecule molecule,
        int ownerId
    ) {}
}
