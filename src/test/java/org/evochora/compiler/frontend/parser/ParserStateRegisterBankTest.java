package org.evochora.compiler.frontend.parser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ParserStateRegisterBankTest {

    @Test
    void aFreshStateHasNoBankAdded() {
        // Whether a bank may be named everywhere is the bank's own property, which the .REG
        // handler reads from the instruction set; the state only tracks what scopes opened up.
        ParserState state = new ParserState();

        assertThat(state.isRegisterBankAvailable("DR")).isFalse();
        assertThat(state.isRegisterBankAvailable("LR")).isFalse();
    }

    @Test
    void testProcScopedBanksNotAvailableByDefault() {
        ParserState state = new ParserState();

        assertThat(state.isRegisterBankAvailable("PDR")).isFalse();
        assertThat(state.isRegisterBankAvailable("PLR")).isFalse();
        assertThat(state.isRegisterBankAvailable("FDR")).isFalse();
        assertThat(state.isRegisterBankAvailable("FLR")).isFalse();
    }

    @Test
    void testAddMakesBankAvailable() {
        ParserState state = new ParserState();

        state.addAvailableRegisterBanks("PDR");

        assertThat(state.isRegisterBankAvailable("PDR")).isTrue();
    }

    @Test
    void testRemoveAfterAddMakesBankUnavailable() {
        ParserState state = new ParserState();

        state.addAvailableRegisterBanks("PDR");
        state.removeAvailableRegisterBanks("PDR");

        assertThat(state.isRegisterBankAvailable("PDR")).isFalse();
    }

    @Test
    void testReferenceCountingDoubleAddSingleRemove() {
        ParserState state = new ParserState();

        state.addAvailableRegisterBanks("PDR");
        state.addAvailableRegisterBanks("PDR");
        state.removeAvailableRegisterBanks("PDR");

        assertThat(state.isRegisterBankAvailable("PDR")).isTrue();

        state.removeAvailableRegisterBanks("PDR");

        assertThat(state.isRegisterBankAvailable("PDR")).isFalse();
    }

    @Test
    void testRemoveWithoutAddIsNoOp() {
        ParserState state = new ParserState();

        state.removeAvailableRegisterBanks("PDR");

        assertThat(state.isRegisterBankAvailable("PDR")).isFalse();
    }

    @Test
    void testMultipleBanksAtOnce() {
        ParserState state = new ParserState();

        state.addAvailableRegisterBanks("PDR", "FDR");

        assertThat(state.isRegisterBankAvailable("PDR")).isTrue();
        assertThat(state.isRegisterBankAvailable("FDR")).isTrue();

        state.removeAvailableRegisterBanks("PDR", "FDR");

        assertThat(state.isRegisterBankAvailable("PDR")).isFalse();
        assertThat(state.isRegisterBankAvailable("FDR")).isFalse();
    }
}
