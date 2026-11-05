package org.evochora.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Walks the chain of an exception and its causes, for every place that looks into it: for the
 * innermost cause, for a cause of a given type, or for the line a known failure is logged with.
 * <p>
 * Java rejects a throwable given itself as its cause, but not a ring of two or more, which the
 * public API can build and a library may hand on. A walk that stops only at the end of the chain
 * would never stop on such a ring, and it would hold whatever thread and lock it runs under. Every
 * walk here therefore goes through {@link #links(Throwable)}, which ends at the first link that
 * comes round again.
 * <p>
 * The line for the log carries no stack trace: the trace exists to find out what went wrong, and
 * for a known failure that is known. What the operator needs is the diagnosis the cause chain
 * holds - a pool that cannot connect wraps the refused connection, a console that cannot start
 * wraps the port in use - and that is what {@link #messages(Throwable)} puts into the line.
 */
public final class CauseChain {

    private static final String SEPARATOR = "; caused by: ";

    private CauseChain() {
    }

    /**
     * The throwable and every cause below it, outermost first, each once: the walk ends at the end
     * of the chain or at the first link that has already been passed.
     *
     * @param throwable the exception whose chain is walked, or {@code null}
     * @return the links of the chain, empty for {@code null}
     */
    public static List<Throwable> links(Throwable throwable) {
        List<Throwable> links = new ArrayList<>();
        Set<Throwable> passed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = throwable; current != null && passed.add(current); current = current.getCause()) {
            links.add(current);
        }
        return links;
    }

    /**
     * The innermost cause of the chain, or the throwable itself if it has none.
     *
     * @param throwable the exception whose chain is walked, not {@code null}
     * @return the last link of the chain
     */
    public static Throwable root(Throwable throwable) {
        List<Throwable> links = links(throwable);
        return links.get(links.size() - 1);
    }

    /**
     * The outermost link of the given type, or {@code null} if none is of that type.
     *
     * @param <T> the type looked for
     * @param throwable the exception whose chain is walked
     * @param type the type looked for
     * @return the outermost link of that type, or {@code null}
     */
    public static <T extends Throwable> T find(Throwable throwable, Class<T> type) {
        for (Throwable link : links(throwable)) {
            if (type.isInstance(link)) {
                return type.cast(link);
            }
        }
        return null;
    }

    /**
     * Joins the messages of the throwable and every cause below it. A cause without a message
     * contributes its class name; a cause repeating its wrapper's message is left out, so that a
     * wrapper that only re-states its cause does not double the line. Line breaks inside a
     * message - a database driver appends the statement on a line of its own - become spaces,
     * because the line this renders is logged without a stack trace and stays one line.
     *
     * @param throwable the exception whose chain is rendered
     * @return the messages, outermost first, separated by {@value #SEPARATOR}
     */
    public static String messages(Throwable throwable) {
        StringBuilder line = new StringBuilder();
        String previous = null;
        for (Throwable current : links(throwable)) {
            String message = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
            if (message.equals(previous)) {
                continue;
            }
            if (line.length() > 0) {
                line.append(SEPARATOR);
            }
            line.append(message.replace('\r', ' ').replace('\n', ' '));
            previous = message;
        }
        return line.toString();
    }
}
