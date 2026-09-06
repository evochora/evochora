package org.evochora.runtime.thermodynamics.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.typesafe.config.ConfigFactory;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.ConflictResolutionStatus;
import org.evochora.runtime.isa.Instruction.Operand;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.thermodynamics.ThermodynamicContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PokeThermodynamicPolicy}, focusing on the rule that a write
 * onto an occupied target cell fails in execution and therefore carries no write
 * thermodynamics - neither energy nor entropy.
 */
@Tag("unit")
class PokeThermodynamicPolicyTest {

    private PokeThermodynamicPolicy policy() {
        var policy = new PokeThermodynamicPolicy();
        policy.initialize(ConfigFactory.parseString("""
            CODE: { energy = 5, entropy = -500 }
            """));
        return policy;
    }

    private ThermodynamicContext writeContext(Molecule toWrite, Molecule targetMolecule, String instructionName) {
        return writeContext(toWrite, targetMolecule, instructionName, 0);
    }

    /**
     * Creates a write context for an organism whose marker register holds the given value. The
     * marker register decides the stored form of the written molecule, which is what the type
     * rules are resolved for.
     */
    private ThermodynamicContext writeContext(Molecule toWrite, Molecule targetMolecule,
                                              String instructionName, int markerRegister) {
        Instruction instruction = mock(Instruction.class);
        when(instruction.getConflictStatus()).thenReturn(ConflictResolutionStatus.NOT_APPLICABLE);
        when(instruction.getName()).thenReturn(instructionName);

        Organism organism = mock(Organism.class);
        when(organism.getId()).thenReturn(1);
        when(organism.getMr()).thenReturn(markerRegister);

        List<Operand> operands = List.of(new Operand(toWrite.toInt(), 0));
        Optional<ThermodynamicContext.TargetInfo> targetInfo = (targetMolecule == null)
                ? Optional.empty()
                : Optional.of(new ThermodynamicContext.TargetInfo(new int[]{0, 0}, targetMolecule, 0));
        return new ThermodynamicContext(instruction, organism, null, operands, targetInfo);
    }

    @Test
    void writeOfDataIsPricedByTheRuleOfItsStoredForm() {
        var policy = new PokeThermodynamicPolicy();
        policy.initialize(ConfigFactory.parseString("""
            DATA:  { energy = 6, entropy = -60 }
            STATE: { energy = 2, entropy = -20 }
            """));

        Molecule empty = new Molecule(Config.TYPE_CODE, 0, 0);

        // With marker register 0 the DATA value is stored as STATE, so the STATE rule applies.
        ThermodynamicContext ephemeral = writeContext(new Molecule(Config.TYPE_DATA, 50, 0), empty, "POKE", 0);
        assertThat(policy.getEnergyCost(ephemeral)).isEqualTo(2);
        assertThat(policy.getEntropyDelta(ephemeral)).isEqualTo(-20);

        // With a non-zero marker register the molecule stays DATA and is priced by the DATA rule.
        ThermodynamicContext durable = writeContext(new Molecule(Config.TYPE_DATA, 50, 0), empty, "POKE", 3);
        assertThat(policy.getEnergyCost(durable)).isEqualTo(6);
        assertThat(policy.getEntropyDelta(durable)).isEqualTo(-60);
    }

    @Test
    void writeOnEmptyTargetChargesEnergyAndEntropy() {
        var policy = policy();
        ThermodynamicContext ctx = writeContext(
                new Molecule(Config.TYPE_CODE, 42, 0), new Molecule(Config.TYPE_CODE, 0, 0), "POKE");
        assertThat(policy.getEnergyCost(ctx)).isEqualTo(5);
        assertThat(policy.getEntropyDelta(ctx)).isEqualTo(-500);
    }

    @Test
    void writeOnOccupiedTargetChargesNeitherWriteEnergyNorWriteEntropy() {
        var policy = policy();
        // A POKE onto an occupied cell fails in execution and must not book any write
        // thermodynamics - neither the energy cost nor the entropy dissipation.
        ThermodynamicContext ctx = writeContext(
                new Molecule(Config.TYPE_CODE, 42, 0), new Molecule(Config.TYPE_DATA, 7, 0), "POKE");
        assertThat(policy.getEnergyCost(ctx)).isEqualTo(0);
        assertThat(policy.getEntropyDelta(ctx)).isEqualTo(0);
    }

    @Test
    void ppkWriteOnOccupiedTargetIsStillCharged() {
        var policy = policy();
        // PPK instructions peek first, which empties the cell, so their write succeeds
        // even when the target currently holds a molecule - the write costs apply.
        ThermodynamicContext ctx = writeContext(
                new Molecule(Config.TYPE_CODE, 42, 0), new Molecule(Config.TYPE_DATA, 7, 0), "PPKR");
        assertThat(policy.getEnergyCost(ctx)).isEqualTo(5);
        assertThat(policy.getEntropyDelta(ctx)).isEqualTo(-500);
    }

    @Test
    void aTypeWithoutARuleAndWithoutADefaultIsPricedAtNothing() {
        var policy = policy();
        // The configuration of policy() holds a CODE rule and no _default block, so a DATA
        // write is not covered by any rule and contributes neither energy nor entropy.
        ThermodynamicContext ctx = writeContext(
                new Molecule(Config.TYPE_DATA, 42, 0), new Molecule(Config.TYPE_CODE, 0, 0), "POKE", 3);
        assertThat(policy.getEnergyCost(ctx)).isEqualTo(0);
        assertThat(policy.getEntropyDelta(ctx)).isEqualTo(0);
    }
}
