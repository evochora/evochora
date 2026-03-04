package org.evochora.compiler.backend.emit;

import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrValue;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RegisterAliasEmissionContributor")
class RegisterAliasEmissionContributorTest {

    private final RegisterAliasEmissionContributor contributor = new RegisterAliasEmissionContributor();

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
    @DisplayName("Should resolve %PR register alias")
    void resolvesPrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.TMP", "%PR2"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.TMP", Instruction.PR_BASE + 2);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %FPR register alias")
    void resolvesFprAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.FLOAT_TMP", "%FPR3"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.FLOAT_TMP", Instruction.FPR_BASE + 3);
    }

    @Test
    @Tag("unit")
    @DisplayName("Should resolve %LR register alias")
    void resolvesLrAlias() {
        EmissionContext ctx = runContributor(regAliasDirective("MOD.LINK", "%LR1"));

        assertThat(ctx.registerAliasMap()).containsEntry("MOD.LINK", Instruction.LR_BASE + 1);
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
        contributor.onItem(regAliasDirective("A.Y", "%PR1"), ctx);
        contributor.onItem(regAliasDirective("B.Z", "%LR0"), ctx);

        assertThat(ctx.registerAliasMap()).hasSize(3);
        assertThat(ctx.registerAliasMap()).containsEntry("A.X", 0);
        assertThat(ctx.registerAliasMap()).containsEntry("A.Y", Instruction.PR_BASE + 1);
        assertThat(ctx.registerAliasMap()).containsEntry("B.Z", Instruction.LR_BASE);
    }

    private static IrDirective regAliasDirective(String name, String register) {
        return new IrDirective("reg", "reg_alias", Map.of(
                "name", new IrValue.Str(name),
                "register", new IrValue.Str(register)
        ), null);
    }
}
