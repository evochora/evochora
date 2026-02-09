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
 * Unit tests for {@link UniversalThermodynamicPolicy}, focusing on the value-specific
 * override feature in write-rules and read-rules.
 */
@Tag("unit")
class UniversalThermodynamicPolicyTest {

    /**
     * Creates a ThermodynamicContext for write-rule testing (POKE-like).
     * The context has no targetInfo (writing to an empty cell) and a single operand
     * containing the molecule to write.
     */
    private ThermodynamicContext writeContext(Molecule toWrite) {
        Instruction instruction = mock(Instruction.class);
        when(instruction.getConflictStatus()).thenReturn(ConflictResolutionStatus.NOT_APPLICABLE);
        when(instruction.getName()).thenReturn("POKE");

        Organism organism = mock(Organism.class);
        when(organism.getId()).thenReturn(1);

        List<Operand> operands = List.of(new Operand(toWrite.toInt(), 0));

        return new ThermodynamicContext(instruction, organism, null, operands, Optional.empty());
    }

    /**
     * Creates a ThermodynamicContext for read-rule testing (PEEK-like).
     * The context has targetInfo containing the molecule being read.
     */
    private ThermodynamicContext readContext(Molecule target, int targetOwnerId, int organismId) {
        Instruction instruction = mock(Instruction.class);
        when(instruction.getConflictStatus()).thenReturn(ConflictResolutionStatus.NOT_APPLICABLE);
        when(instruction.getName()).thenReturn("PEEK");

        Organism organism = mock(Organism.class);
        when(organism.getId()).thenReturn(organismId);

        var targetInfo = new ThermodynamicContext.TargetInfo(new int[]{0, 0}, target, targetOwnerId);
        return new ThermodynamicContext(instruction, organism, null, List.of(), Optional.of(targetInfo));
    }

    @Test
    void writeRuleValueOverrideAppliesToMatchingValue() {
        var policy = new UniversalThermodynamicPolicy();
        policy.initialize(ConfigFactory.parseString("""
            base-energy = 0
            base-entropy = 0
            write-rules: {
              CODE: {
                energy = 5, entropy = -50
                values: {
                  "0": { energy = 1, entropy = -10 }
                }
              }
            }
            """));

        // CODE:0 (NOP) should use the value override: energy=1, entropy=-10
        ThermodynamicContext nopCtx = writeContext(new Molecule(Config.TYPE_CODE, 0, 0));
        assertThat(policy.getEnergyCost(nopCtx)).isEqualTo(1);
        assertThat(policy.getEntropyDelta(nopCtx)).isEqualTo(-10);
    }

    @Test
    void writeRuleFallsBackToTypeDefaultWhenNoValueMatch() {
        var policy = new UniversalThermodynamicPolicy();
        policy.initialize(ConfigFactory.parseString("""
            base-energy = 0
            base-entropy = 0
            write-rules: {
              CODE: {
                energy = 5, entropy = -50
                values: {
                  "0": { energy = 1, entropy = -10 }
                }
              }
            }
            """));

        // CODE:42 should use the type default: energy=5, entropy=-50
        ThermodynamicContext codeCtx = writeContext(new Molecule(Config.TYPE_CODE, 42, 0));
        assertThat(policy.getEnergyCost(codeCtx)).isEqualTo(5);
        assertThat(policy.getEntropyDelta(codeCtx)).isEqualTo(-50);
    }

    @Test
    void writeRuleWithoutValuesBlockIsBackwardCompatible() {
        var policy = new UniversalThermodynamicPolicy();
        policy.initialize(ConfigFactory.parseString("""
            base-energy = 0
            base-entropy = 0
            write-rules: {
              CODE: { energy = 5, entropy = -50 }
            }
            """));

        // Both CODE:0 and CODE:42 should cost the same (no value overrides)
        ThermodynamicContext nopCtx = writeContext(new Molecule(Config.TYPE_CODE, 0, 0));
        ThermodynamicContext codeCtx = writeContext(new Molecule(Config.TYPE_CODE, 42, 0));

        assertThat(policy.getEnergyCost(nopCtx)).isEqualTo(5);
        assertThat(policy.getEnergyCost(codeCtx)).isEqualTo(5);
    }

    @Test
    void readRuleValueOverrideAppliesToMatchingValue() {
        var policy = new UniversalThermodynamicPolicy();
        policy.initialize(ConfigFactory.parseString("""
            base-energy = 0
            base-entropy = 0
            read-rules: {
              own: {
                CODE: {
                  energy = 3, entropy = 1
                  values: {
                    "0": { energy = 0, entropy = 0 }
                  }
                }
              }
            }
            """));

        // PEEKing own CODE:0 → value override: energy=0
        ThermodynamicContext nopCtx = readContext(new Molecule(Config.TYPE_CODE, 0, 0), 1, 1);
        assertThat(policy.getEnergyCost(nopCtx)).isEqualTo(0);
        assertThat(policy.getEntropyDelta(nopCtx)).isEqualTo(0);

        // PEEKing own CODE:42 → type default: energy=3
        ThermodynamicContext codeCtx = readContext(new Molecule(Config.TYPE_CODE, 42, 0), 1, 1);
        assertThat(policy.getEnergyCost(codeCtx)).isEqualTo(3);
        assertThat(policy.getEntropyDelta(codeCtx)).isEqualTo(1);
    }

    @Test
    void multipleValueOverridesResolvCorrectly() {
        var policy = new UniversalThermodynamicPolicy();
        policy.initialize(ConfigFactory.parseString("""
            base-energy = 0
            base-entropy = 0
            write-rules: {
              CODE: {
                energy = 5, entropy = -50
                values: {
                  "0": { energy = 1, entropy = -10 }
                  "1": { energy = 2, entropy = -20 }
                }
              }
            }
            """));

        ThermodynamicContext ctx0 = writeContext(new Molecule(Config.TYPE_CODE, 0, 0));
        ThermodynamicContext ctx1 = writeContext(new Molecule(Config.TYPE_CODE, 1, 0));
        ThermodynamicContext ctx99 = writeContext(new Molecule(Config.TYPE_CODE, 99, 0));

        assertThat(policy.getEnergyCost(ctx0)).isEqualTo(1);
        assertThat(policy.getEnergyCost(ctx1)).isEqualTo(2);
        assertThat(policy.getEnergyCost(ctx99)).isEqualTo(5);
    }

    @Test
    void valueOverrideCanUsePermille() {
        var policy = new UniversalThermodynamicPolicy();
        policy.initialize(ConfigFactory.parseString("""
            base-energy = 0
            base-entropy = 0
            write-rules: {
              CODE: {
                energy = 5, entropy = -50
                values: {
                  "0": { energy = 0, energy-permille = 1000, entropy = -50 }
                }
              }
            }
            """));

        // CODE:0 with permille: 0 + (0 * 1000 / 1000) = 0
        ThermodynamicContext ctx0 = writeContext(new Molecule(Config.TYPE_CODE, 0, 0));
        assertThat(policy.getEnergyCost(ctx0)).isEqualTo(0);

        // CODE:42 falls back to type default: energy=5
        ThermodynamicContext ctx42 = writeContext(new Molecule(Config.TYPE_CODE, 42, 0));
        assertThat(policy.getEnergyCost(ctx42)).isEqualTo(5);
    }
}
