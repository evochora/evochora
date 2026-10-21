package org.evochora.compiler.api;

/**
 * A note for a source view at a full position, before it is put into the entry or the instance
 * of injected tokens the position belongs to: {@link SourceView#withNotes} puts it there as a
 * {@link SourceFile.Note}.
 *
 * @param at   The position the note stands at: its placement, file, instance, line and column.
 * @param text The text of the note.
 */
public record PositionedNote(SourceInfo at, String text) {
}
