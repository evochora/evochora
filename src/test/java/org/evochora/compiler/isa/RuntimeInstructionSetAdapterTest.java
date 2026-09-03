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
}
