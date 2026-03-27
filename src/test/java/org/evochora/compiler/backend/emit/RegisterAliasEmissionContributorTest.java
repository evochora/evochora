package org.evochora.compiler.backend.emit;

import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrValue;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RegisterAliasEmissionContributor")
class RegisterAliasEmissionContributorTest {

    private final org.evochora.compiler.features.reg.RegisterAliasEmissionContributor contributor =
            new org.evochora.compiler.features.reg.RegisterAliasEmissionContributor();

    @BeforeAll
    static void initIsa() {
        Instruction.init();
    }

    private EmissionContext runContributor(IrDirective directive) {
        EmissionContext ctx = new EmissionContext();
        contributor.onItem(directive, ctx);
        return ctx;
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %DR register alias")
    void resolvesDrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.COUNTER", "%DR7"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.COUNTER", 7);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %PDR register alias")
    void resolvesPdrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.TMP", "%PDR2"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.TMP", RegisterBank.PDR.base + 2);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %FDR register alias")
    void resolvesFdrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.FLOAT_TMP", "%FDR3"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.FLOAT_TMP", RegisterBank.FDR.base + 3);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %LR register alias")
    void resolvesLrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.LINK", "%LR1"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.LINK", RegisterBank.LR.base + 1);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %PLR register alias")
    void resolvesPlrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.POS", "%PLR1"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.POS", RegisterBank.PLR.base + 1);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %FLR register alias")
    void resolvesFlrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.LOC_PARAM", "%FLR0"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.LOC_PARAM", RegisterBank.FLR.base);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %SDR register alias")
    void resolvesSdrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.STATE", "%SDR5"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.STATE", RegisterBank.SDR.base + 5);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %SLR register alias")
    void resolvesSlrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.SAVED_POS", "%SLR2"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.SAVED_POS", RegisterBank.SLR.base + 2);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should ignore non-reg_alias directives")
    void ignoresOtherDirectives() {
        EmissionContext ctx = new EmissionContext();
        contributor.onItem(new IrDirective("core", "proc_enter", Map.of(
                "name", new IrValue.Str("X")), null), ctx);

        assertThat(ctx.registerAliasMap()).isEmpty();
    }

    @Test
    @Tag("unit")
    @DisplayName("Should ignore non-directive IR items")
    void ignoresNonDirectives() {
        EmissionContext ctx = new EmissionContext();
        contributor.onItem(new IrInstruction("NOP", List.of(), null), ctx);

        assertThat(ctx.registerAliasMap()).isEmpty();
    }

    @Test
    @Tag("unit")
    @DisplayName("Should handle multiple aliases")
    void handlesMultipleAliases() {
        EmissionContext ctx = new EmissionContext();
        contributor.onItem(regAliasDirective("A.X", "%DR0"), ctx);
        contributor.onItem(regAliasDirective("A.Y", "%PDR1"), ctx);
        contributor.onItem(regAliasDirective("B.Z", "%LR0"), ctx);

        assertThat(ctx.registerAliasMap()).hasSize(3);
        assertThat(ctx.registerAliasMap()).containsEntry("A.X", 0);
        assertThat(ctx.registerAliasMap()).containsEntry("A.Y", RegisterBank.PDR.base + 1);
        assertThat(ctx.registerAliasMap()).containsEntry("B.Z", RegisterBank.LR.base);
    }

    private static IrDirective regAliasDirective(String name, String register) {
        return new IrDirective("reg", "reg_alias", Map.of(
                "name", new IrValue.Str(name),
                "register", new IrValue.Str(register)
        ), null);
    }
}
