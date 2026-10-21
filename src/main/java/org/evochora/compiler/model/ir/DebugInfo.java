package org.evochora.compiler.model.ir;

import org.evochora.compiler.api.PositionedNote;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.SourceView;
import org.evochora.compiler.api.TokenInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * What a program carries for a debugger and a source view besides its items: the text of its
 * files, the classification of its tokens, the values of the computations the frontend computed
 * and the defines and values of the compilation's options. No phase of the backend reads or
 * changes it; the emitter copies it into the artifact, with the notes put into the view, and
 * puts the defines and values into the program's identity.
 *
 * @param view     One entry per inclusion of a file and every instance of injected tokens that
 *                 is no inclusion, with the regions and notes the preprocessor recorded in them.
 * @param tokenMap The classification of every token by its source position.
 * @param notes    The note {@code [=value]} after the last token of every computation the frontend
 *                 computed, each distinct note once, not yet in the view.
 * @param flags    The defines of the compilation's options as the options normalise them: each
 *                 name upper-cased, each value an integer or empty for a name defined without
 *                 one. The regions and notes of the sources were recorded under them.
 * @param values   The values of the compilation's options as the options normalise them: each
 *                 name upper-cased, each value the text of an operand.
 */
public record DebugInfo(SourceView view, Map<SourceInfo, TokenInfo> tokenMap, List<PositionedNote> notes,
                        Map<String, OptionalLong> flags, Map<String, String> values) {

    /**
     * Makes the record immutable by copying the list and the maps; the copy of the token map
     * keeps the iteration order of the given one.
     */
    public DebugInfo {
        tokenMap = Collections.unmodifiableMap(new LinkedHashMap<>(tokenMap));
        notes = List.copyOf(notes);
        flags = Map.copyOf(flags);
        values = Map.copyOf(values);
    }

    /**
     * Returns the debug information of a program without files, instances, tokens, notes,
     * defines or values.
     *
     * @return An empty instance.
     */
    public static DebugInfo none() {
        return new DebugInfo(SourceView.none(), Map.of(), List.of(), Map.of(), Map.of());
    }
}
