package org.evochora.compiler.backend.emit;

import org.evochora.compiler.features.proc.ProcedureEmissionContributor;
import org.evochora.compiler.api.ParamInfo;
import org.evochora.compiler.api.ParamType;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProcedureEmissionContributor")
class ProcedureEmissionContributorTest {

    private final ProcedureEmissionContributor contributor = new ProcedureEmissionContributor();

    private EmissionContext runContributor(IrDirective directive) {
        EmissionContext ctx = new EmissionContext();
        contributor.onItem(directive, ctx);
        return ctx;
    }

    @Test
    @Tag("unit")
    @DisplayName("Should extract REF and VAL parameters from proc_enter")
    void extractsRefAndValParams() {
        Map<String, IrValue> args = new HashMap<>();
        args.put("name", new IrValue.Str("MOD.MY_PROC"));
        args.put("refParams", new IrValue.ListVal(List.of(
                new IrValue.Str("A"), new IrValue.Str("B"))));
        args.put("valParams", new IrValue.ListVal(List.of(
                new IrValue.Str("X"))));

        EmissionContext ctx = runContributor(
                new IrDirective("core", "proc_enter", args, null));

        Map<String, List<ParamInfo>> result = ctx.procNameToParamNames();
        assertThat(result).containsKey("MOD.MY_PROC");

        List<ParamInfo> params = result.get("MOD.MY_PROC");
        assertThat(params).hasSize(3);
        assertThat(params.get(0)).isEqualTo(new ParamInfo("A", ParamType.REF));
        assertThat(params.get(1)).isEqualTo(new ParamInfo("B", ParamType.REF));
        assertThat(params.get(2)).isEqualTo(new ParamInfo("X", ParamType.VAL));
    }

    @Test
    @Tag("unit")
    @DisplayName("Should handle procedure with no parameters")
    void handlesNoParams() {
        Map<String, IrValue> args = new HashMap<>();
        args.put("name", new IrValue.Str("SIMPLE"));

        EmissionContext ctx = runContributor(
                new IrDirective("core", "proc_enter", args, null));

        List<ParamInfo> params = ctx.procNameToParamNames().get("SIMPLE");
        assertThat(params).isEmpty();
    }

    @Test
    @Tag("unit")
    @DisplayName("Should ignore non-proc_enter directives")
    void ignoresOtherDirectives() {
        Map<String, IrValue> args = new HashMap<>();
        args.put("name", new IrValue.Str("X"));

        EmissionContext ctx = new EmissionContext();
        contributor.onItem(new IrDirective("core", "proc_exit", args, null), ctx);

        assertThat(ctx.procNameToParamNames()).isEmpty();
    }

    @Test
    @Tag("unit")
    @DisplayName("Should ignore non-directive IR items")
    void ignoresNonDirectives() {
        EmissionContext ctx = new EmissionContext();
        contributor.onItem(new IrInstruction("NOP", List.of(), null), ctx);

        assertThat(ctx.procNameToParamNames()).isEmpty();
    }

    @Test
    @Tag("unit")
    @DisplayName("Should handle multiple procedures")
    void handlesMultipleProcedures() {
        EmissionContext ctx = new EmissionContext();

        Map<String, IrValue> args1 = new HashMap<>();
        args1.put("name", new IrValue.Str("MOD.PROC_A"));
        args1.put("refParams", new IrValue.ListVal(List.of(new IrValue.Str("P"))));
        contributor.onItem(new IrDirective("core", "proc_enter", args1, null), ctx);

        Map<String, IrValue> args2 = new HashMap<>();
        args2.put("name", new IrValue.Str("MOD.PROC_B"));
        args2.put("valParams", new IrValue.ListVal(List.of(new IrValue.Str("Q"))));
        contributor.onItem(new IrDirective("core", "proc_enter", args2, null), ctx);

        assertThat(ctx.procNameToParamNames()).hasSize(2);
        assertThat(ctx.procNameToParamNames().get("MOD.PROC_A").get(0).type()).isEqualTo(ParamType.REF);
        assertThat(ctx.procNameToParamNames().get("MOD.PROC_B").get(0).type()).isEqualTo(ParamType.VAL);
    }
}
