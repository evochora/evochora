package org.evochora.compiler.isa;

import org.evochora.compiler.isa.IInstructionSet.RegisterBankInfo;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compiler's view of the register banks is the runtime's declaration, bank for bank.
 */
@Tag("unit")
class RuntimeInstructionSetAdapterTest {

    private final IInstructionSet isa = new RuntimeInstructionSetAdapter();

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void everyBankOfTheRuntimeAppearsOnceInDeclarationOrder() {
        List<RegisterBankInfo> banks = isa.registerBanks();

        assertThat(banks).extracting(RegisterBankInfo::name)
                .containsExactly(java.util.Arrays.stream(RegisterBank.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    void eachBankCarriesTheRuntimesProperties() {
        List<RegisterBankInfo> banks = isa.registerBanks();

        for (RegisterBank bank : RegisterBank.values()) {
            RegisterBankInfo info = banks.get(bank.ordinal());
            assertThat(info.prefix()).isEqualTo(bank.prefix);
            assertThat(info.base()).isEqualTo(bank.base);
            assertThat(info.count()).isEqualTo(bank.count);
            assertThat(info.location()).isEqualTo(bank.isLocation);
            assertThat(info.forbidden()).isEqualTo(bank.isForbidden);
            assertThat(info.alwaysAvailable()).isEqualTo(bank.isAlwaysAvailable);
            assertThat(info.procScoped()).isEqualTo(RegisterBank.allProcScoped().contains(bank));
        }
    }

    @Test
    void aRegisterTokenResolvesToTheBanksBasePlusItsIndex() {
        for (RegisterBankInfo bank : isa.registerBanks()) {
            if (bank.count() == 0) continue;
            assertThat(isa.resolveRegisterToken(bank.prefix() + (bank.count() - 1)))
                    .contains(bank.base() + bank.count() - 1);
        }
    }

    @Test
    void typedLiteralsAreRequiredExactlyWhenTheRuntimeTypesStrictly() {
        assertThat(isa.requiresTypedLiterals()).isEqualTo(Config.STRICT_TYPING);
    }

    @Test
    void aRegisterTextIsReadIntoItsBankAndIndex() {
        IInstructionSet.RegisterRef ref = isa.parseRegister("%dr3").orElseThrow();

        assertThat(ref.bank().name()).isEqualTo("DR");
        assertThat(ref.index()).isEqualTo(3);
        assertThat(ref.inBounds()).isTrue();
        assertThat(ref.id()).isEqualTo(ref.bank().base() + 3);
    }

    @Test
    void anIndexBeyondTheBankIsReadButOutOfBounds() {
        IInstructionSet.RegisterRef ref = isa.parseRegister("%DR999").orElseThrow();

        assertThat(ref.bank().name()).isEqualTo("DR");
        assertThat(ref.inBounds()).isFalse();
    }

    @Test
    void anOverlongIndexIsReadAsOutOfBounds() {
        IInstructionSet.RegisterRef ref = isa.parseRegister("%DR99999999999999999999").orElseThrow();

        assertThat(ref.inBounds()).isFalse();
    }

    @Test
    void aWordThatIsNoRegisterStaysAWord() {
        // An alias like %DR_TMP or a name with no bank prefix is not a register
        assertThat(isa.parseRegister("%DR_TMP")).isEmpty();
        assertThat(isa.parseRegister("%XR0")).isEmpty();
        assertThat(isa.parseRegister("%DR")).isEmpty();
    }

    @Test
    void moleculeTypesAreTheRuntimes() {
        assertThat(isa.moleculeType("DATA")).contains(Config.TYPE_DATA);
        assertThat(isa.moleculeType("structure")).contains(Config.TYPE_STRUCTURE);
        assertThat(isa.moleculeType("FOO")).isEmpty();
    }

    @Test
    void aCellIsPackedAsTheRuntimePacksAMolecule() {
        assertThat(isa.encodeCell(Config.TYPE_DATA, 42))
                .isEqualTo(new org.evochora.runtime.model.Molecule(Config.TYPE_DATA, 42).toInt());
    }

    @Test
    void aLabelValueNeverLeavesTheLabelValueBits() {
        for (String name : List.of("START", "MAIN.LOOP", "_safe_call_7", "", "a very long label name indeed")) {
            int value = isa.labelValue(name);
            assertThat(value).isBetween(0, Config.LABEL_VALUE_MASK);
        }
    }
}
