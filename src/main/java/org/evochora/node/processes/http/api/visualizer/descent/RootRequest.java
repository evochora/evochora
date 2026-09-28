package org.evochora.node.processes.http.api.visualizer.descent;

/**
 * The root a tick request names: an organism id, the virtual root {@code all} above the
 * founders, or {@code auto}, the common ancestor of the organisms alive at the tick.
 * <p>
 * Immutable and thread-safe.
 *
 * @param kind Which of the three forms was requested
 * @param id   The organism id for {@link Kind#ORGANISM}, 0 otherwise
 */
public record RootRequest(Kind kind, int id) {

    /** The three forms a root can be requested in. */
    public enum Kind {
        /** One organism, by id. */
        ORGANISM,
        /** The virtual root above the founders. */
        ALL,
        /** The common ancestor of the living, resolved at the requested tick. */
        AUTO
    }

    /**
     * Parses the value of the {@code root} query parameter.
     *
     * @param value {@code <id>}, {@code all} or {@code auto}
     * @return The request
     * @throws IllegalArgumentException if the value is none of these, or the id is not positive;
     *                                  never taken as {@code all}
     */
    public static RootRequest parse(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("root must be an organism id, 'all' or 'auto'");
        }
        final String trimmed = value.trim();
        if ("all".equals(trimmed)) {
            return new RootRequest(Kind.ALL, 0);
        }
        if ("auto".equals(trimmed)) {
            return new RootRequest(Kind.AUTO, 0);
        }
        final int id;
        try {
            id = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("root must be an organism id, 'all' or 'auto': " + value, e);
        }
        if (id <= 0) {
            throw new IllegalArgumentException("root must be a positive organism id: " + value);
        }
        return new RootRequest(Kind.ORGANISM, id);
    }

    /**
     * The request as it was spelled, for cache keys.
     *
     * @return {@code all}, {@code auto} or the id
     */
    public String token() {
        return switch (kind) {
            case ALL -> "all";
            case AUTO -> "auto";
            case ORGANISM -> String.valueOf(id);
        };
    }
}
