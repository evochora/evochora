package org.evochora.compiler.api;

import java.util.List;

/**
 * An instance of injected tokens that is no inclusion of a file: tokens copied from a template
 * that stands at {@code definedAt} and injected at {@code calledAt}. The copies keep the lines of
 * the template, so their positions name the template's placement and file and carry the
 * instance's number as their expansion. The name and the bindings are words of the feature that
 * injected the tokens, carried as text.
 * <p>
 * Besides where it came from, the instance carries what the preprocessor recorded in it for a
 * source view: the regions of lines it left out and the notes it attached to positions, each with
 * the instance's number as its expansion.
 *
 * @param calledAt  The position at which the tokens were injected.
 * @param definedAt The position of the template as it stood when it was read; its placement, file
 *                  and expansion name the entry, or the enclosing instance, holding the
 *                  template's lines.
 * @param name      The name under which the template was injected.
 * @param bindings  The words the template's parameters were bound to, in the order of the
 *                  parameters.
 * @param leftOut   The regions of lines left out in this instance, in the order they were
 *                  recorded.
 * @param notes     The notes at positions of this instance, in the order they were recorded.
 */
public record Expansion(SourceInfo calledAt, SourceInfo definedAt, String name, List<Binding> bindings,
                        List<SourceFile.LeftOut> leftOut, List<SourceFile.Note> notes) {

    /**
     * A parameter of the template and the text it was bound to.
     *
     * @param parameter The name of the parameter.
     * @param argument  The text of the argument as written, with a space between two of its words
     *                  where the program had one.
     */
    public record Binding(String parameter, String argument) {
    }

    /**
     * Makes the record immutable by copying the lists; a null list becomes an empty one.
     */
    public Expansion {
        bindings = bindings != null ? List.copyOf(bindings) : List.of();
        leftOut = leftOut != null ? List.copyOf(leftOut) : List.of();
        notes = notes != null ? List.copyOf(notes) : List.of();
    }

    /**
     * Returns this instance with the given regions and notes in place of its own.
     *
     * @param newLeftOut The regions of lines left out.
     * @param newNotes   The notes.
     * @return An instance with this origin and the given records.
     */
    public Expansion withRecords(List<SourceFile.LeftOut> newLeftOut, List<SourceFile.Note> newNotes) {
        return new Expansion(calledAt, definedAt, name, bindings, newLeftOut, newNotes);
    }
}
