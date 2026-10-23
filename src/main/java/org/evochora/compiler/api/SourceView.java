package org.evochora.compiler.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The source files of a compilation as a source view shows them: one entry per inclusion and
 * every instance of injected tokens that is no inclusion, by its number, each with the regions
 * and notes recorded in it.
 * <p>
 * A record at a position belongs to the instance that is no inclusion under the position's
 * instance number, or else to the entry of the position's placement, file and instance. The
 * numbers of such instances are never those of an entry, so a record belongs to one of them at
 * most. Records stand in the order of their positions; equal records are kept once, and
 * different records at one position stand side by side, in the order they were given.
 *
 * @param sources    One entry per inclusion of a file.
 * @param expansions Every instance of injected tokens that is no inclusion, by its number.
 */
public record SourceView(List<SourceFile> sources, Map<Integer, Expansion> expansions) {

    /**
     * Makes the record immutable by copying the list and the map, the instances in the order of
     * their numbers.
     */
    public SourceView {
        sources = List.copyOf(sources);
        expansions = Collections.unmodifiableMap(new TreeMap<>(expansions));
    }

    /**
     * Returns a view without entries and instances.
     *
     * @return An empty view.
     */
    public static SourceView none() {
        return new SourceView(List.of(), Map.of());
    }

    /**
     * Reports whether a record at a position of the given placement, file and instance belongs
     * to an entry or an instance of this view.
     *
     * @param placement The alias chain of the position's placement.
     * @param fileName  The file of the position.
     * @param instance  The instance of injected tokens of the position.
     * @return {@code true} if the record has a place in this view.
     */
    public boolean holds(String placement, String fileName, int instance) {
        return expansions.containsKey(instance) || entryKeys().contains(entryKey(placement, fileName, instance));
    }

    /**
     * Returns this view with the given notes added, as {@link #withRecords} adds them.
     *
     * @param notes The notes.
     * @return The view with the notes in their entries and instances.
     */
    public SourceView withNotes(List<PositionedNote> notes) {
        return withRecords(List.of(), notes);
    }

    /**
     * Returns this view with the given regions and notes added to the entries and instances they
     * belong to, with the records each holds already, in the order of their positions. A record
     * equal to one held or given before it is kept once; records that differ at one position stand
     * side by side, in the order they were first held or given.
     *
     * @param leftOut The regions of lines left out.
     * @param notes   The notes.
     * @return The view with the records in their entries and instances.
     * @throws IllegalStateException if a record belongs to no entry and no instance of this view,
     *         which is a defect of the compiler.
     */
    public SourceView withRecords(List<PositionedLeftOut> leftOut, List<PositionedNote> notes) {
        Set<String> entryKeys = entryKeys();
        Map<String, List<SourceFile.LeftOut>> regionsByEntry = new HashMap<>();
        Map<Integer, List<SourceFile.LeftOut>> regionsByInstance = new HashMap<>();
        for (PositionedLeftOut record : leftOut) {
            int instance = record.region().expansion();
            if (expansions.containsKey(instance)) {
                regionsByInstance.computeIfAbsent(instance, k -> new ArrayList<>()).add(record.region());
            } else {
                String key = entryKey(record.placement(), record.fileName(), instance);
                requireEntry(entryKeys, key, "a left-out region at " + record.fileName() + ":"
                        + record.region().directiveLine());
                regionsByEntry.computeIfAbsent(key, k -> new ArrayList<>()).add(record.region());
            }
        }
        Map<String, List<SourceFile.Note>> notesByEntry = new HashMap<>();
        Map<Integer, List<SourceFile.Note>> notesByInstance = new HashMap<>();
        for (PositionedNote record : notes) {
            SourceInfo at = record.at();
            SourceFile.Note note = new SourceFile.Note(at.expansion(), at.lineNumber(), at.columnNumber(), record.text());
            if (expansions.containsKey(at.expansion())) {
                notesByInstance.computeIfAbsent(at.expansion(), k -> new ArrayList<>()).add(note);
            } else {
                String key = entryKey(at.placement(), at.fileName(), at.expansion());
                requireEntry(entryKeys, key, "a note at " + SourceInfo.position(at));
                notesByEntry.computeIfAbsent(key, k -> new ArrayList<>()).add(note);
            }
        }

        List<SourceFile> newSources = new ArrayList<>(sources.size());
        for (SourceFile entry : sources) {
            String key = entryKey(entry.placement(), entry.resolvedPath(), entry.instance());
            newSources.add(entry.withRecords(
                    joined(entry.leftOut(), regionsByEntry.getOrDefault(key, List.of()), REGION_ORDER),
                    joined(entry.notes(), notesByEntry.getOrDefault(key, List.of()), NOTE_ORDER)));
        }
        Map<Integer, Expansion> newExpansions = new LinkedHashMap<>();
        expansions.forEach((instance, expansion) -> newExpansions.put(instance, expansion.withRecords(
                joined(expansion.leftOut(), regionsByInstance.getOrDefault(instance, List.of()), REGION_ORDER),
                joined(expansion.notes(), notesByInstance.getOrDefault(instance, List.of()), NOTE_ORDER))));
        return new SourceView(newSources, newExpansions);
    }

    private static void requireEntry(Set<String> entryKeys, String key, String record) {
        if (!entryKeys.contains(key)) {
            throw new IllegalStateException("Internal error: " + record
                    + " names neither a source entry nor an expansion.");
        }
    }

    /** Regions by the line of their directive, then by their first line. */
    private static final Comparator<SourceFile.LeftOut> REGION_ORDER =
            Comparator.comparingInt(SourceFile.LeftOut::directiveLine).thenComparingInt(SourceFile.LeftOut::from);

    /** Notes by line, then by column. */
    private static final Comparator<SourceFile.Note> NOTE_ORDER =
            Comparator.comparingInt(SourceFile.Note::line).thenComparingInt(SourceFile.Note::column);

    /**
     * The records held and the added ones, each distinct record once, in the order of their
     * positions; records at one position keep the order they were held or given in.
     */
    private static <T> List<T> joined(List<T> held, List<T> added, Comparator<T> order) {
        if (added.isEmpty()) {
            return held;
        }
        Set<T> all = new LinkedHashSet<>(held);
        all.addAll(added);
        List<T> sorted = new ArrayList<>(all);
        sorted.sort(order);
        return List.copyOf(sorted);
    }

    private Set<String> entryKeys() {
        Set<String> keys = new HashSet<>();
        sources.forEach(entry -> keys.add(entryKey(entry.placement(), entry.resolvedPath(), entry.instance())));
        return keys;
    }

    private static String entryKey(String placement, String fileName, int instance) {
        return placement + "\0" + fileName + "\0" + instance;
    }
}
