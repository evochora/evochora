package org.evochora.compiler;

import org.evochora.compiler.features.ctx.CtxFeature;
import org.evochora.compiler.features.define.DefineFeature;
import org.evochora.compiler.features.dir.DirFeature;
import org.evochora.compiler.features.macro.MacroFeature;
import org.evochora.compiler.features.org.OrgFeature;
import org.evochora.compiler.features.label.LabelFeature;
import org.evochora.compiler.features.place.PlaceFeature;
import org.evochora.compiler.features.reg.RegFeature;
import org.evochora.compiler.features.importdir.ImportFeature;
import org.evochora.compiler.features.instruction.InstructionFeature;
import org.evochora.compiler.features.proc.ProcFeature;
import org.evochora.compiler.features.require.RequireFeature;
import org.evochora.compiler.features.repeat.RepeatFeature;
import org.evochora.compiler.features.source.SourceFeature;

import java.util.List;

/**
 * Single Source of Truth for all built-in compiler features.
 * Both {@link Compiler} and tests use this to obtain the canonical feature list.
 */
public final class StandardFeatures {

    private StandardFeatures() {}

    /**
     * Returns all built-in compiler features.
     * Each call creates fresh, stateless feature instances in an unmodifiable list.
     *
     * @return An unmodifiable list of all built-in compiler features.
     */
    public static List<ICompilerFeature> all() {
        // The order is semantically relevant for list-based registrations (emissionRules,
        // linkingRules, emissionContributors). Features are registered in this order —
        // within a feature, the registration order determines execution order.
        // InstructionFeature must be last — it registers the defaultParserStatement handler.
        return List.of(
            new RepeatFeature(),
            new SourceFeature(),
            new MacroFeature(),
            new CtxFeature(),
            new OrgFeature(),
            new DirFeature(),
            new DefineFeature(),
            new RegFeature(),
            new LabelFeature(),
            new PlaceFeature(),
            new RequireFeature(),
            new ImportFeature(),
            new ProcFeature(),
            new InstructionFeature()
        );
    }
}
