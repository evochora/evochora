package org.evochora.runtime.isa;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class RegisterBankTest {

    @Test
    void testForIdReturnsCorrectBankAtBase() {
        for (RegisterBank bank : RegisterBank.values()) {
            if (bank.count == 0) continue;
            assertThat(RegisterBank.forId(bank.base))
                    .as("forId(%d) should return %s", bank.base, bank.name())
                    .isEqualTo(bank);
        }
    }

    @Test
    void testForIdReturnsCorrectBankAtLastValidId() {
        for (RegisterBank bank : RegisterBank.values()) {
            if (bank.count == 0) continue;
            int lastId = bank.base + bank.count - 1;
            assertThat(RegisterBank.forId(lastId))
                    .as("forId(%d) should return %s (last valid ID)", lastId, bank.name())
                    .isEqualTo(bank);
        }
    }

    @Test
    void testForIdDoesNotReturnBankAfterLastValidId() {
        for (RegisterBank bank : RegisterBank.values()) {
            if (bank.count == 0) continue;
            int firstInvalidId = bank.base + bank.count;
            assertThat(RegisterBank.forId(firstInvalidId))
                    .as("forId(%d) should NOT return %s (first ID after bank)", firstInvalidId, bank.name())
                    .isNotEqualTo(bank);
        }
    }

    @Test
    void testForIdDoesNotReturnBankBeforeBase() {
        for (RegisterBank bank : RegisterBank.values()) {
            if (bank.base == 0) continue;
            int idBeforeBase = bank.base - 1;
            assertThat(RegisterBank.forId(idBeforeBase))
                    .as("forId(%d) should NOT return %s (last ID before bank)", idBeforeBase, bank.name())
                    .isNotEqualTo(bank);
        }
    }

    @Test
    void testIsLocationBankMatchesBankProperty() {
        for (RegisterBank bank : RegisterBank.values()) {
            if (bank.count == 0) continue;
            assertThat(RegisterBank.isLocationBank(bank.base))
                    .as("isLocationBank(%d) should be %s for %s", bank.base, bank.isLocation, bank.name())
                    .isEqualTo(bank.isLocation);
        }
    }

    @Test
    void testForIdNegativeReturnsNull() {
        assertThat(RegisterBank.forId(-1)).isNull();
    }

    @Test
    void testForIdAtTableSizeReturnsNull() {
        assertThat(RegisterBank.forId(2048)).isNull();
    }

    @Test
    void testAllSavedOnCallReturnsOnlyStackSavedWithRegisters() {
        for (RegisterBank bank : RegisterBank.allSavedOnCall()) {
            assertThat(bank.callBehavior).isEqualTo(RegisterBank.CallBehavior.STACK_SAVED);
            assertThat(bank.count).isGreaterThan(0);
        }
    }

    @Test
    void testAllProcScopedReturnsOnlyNonGlobalWithRegisters() {
        for (RegisterBank bank : RegisterBank.allProcScoped()) {
            assertThat(bank.callBehavior).isNotEqualTo(RegisterBank.CallBehavior.GLOBAL);
            assertThat(bank.count).isGreaterThan(0);
        }
    }
}
