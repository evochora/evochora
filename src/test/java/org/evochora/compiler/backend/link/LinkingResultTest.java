package org.evochora.compiler.backend.link;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.compiler.model.ir.DebugInfo;
import org.evochora.compiler.model.ir.IrProgram;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The call-site bindings stand in the order of their keys, the bindings of each call too, so
 * that an artifact is written the same way in every run.
 */
@Tag("unit")
class LinkingResultTest {

    @Test
    void callsAndTheirBindingsStandInTheOrderOfTheirKeys() {
        Map<Integer, Map<Integer, Integer>> bindings = new HashMap<>();
        bindings.put(170, Map.of(3, 1, 0, 2, 1, 0));
        bindings.put(83, Map.of(2, 2, 1, 1));
        bindings.put(156, Map.of(0, 0));

        LinkingResult result = new LinkingResult(new IrProgram("p", List.of(), DebugInfo.none()), bindings);

        assertThat(result.callSiteBindings().keySet()).containsExactly(83, 156, 170);
        assertThat(result.callSiteBindings().get(170).keySet()).containsExactly(0, 1, 3);
    }
}
