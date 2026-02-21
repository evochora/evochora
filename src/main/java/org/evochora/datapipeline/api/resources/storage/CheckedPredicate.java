package org.evochora.datapipeline.api.resources.storage;

/**
 * A predicate that can throw checked exceptions.
 * <p>
 * Used by {@link IBatchStorageRead#forEachChunkUntil} to allow early exit from streaming
 * iteration: return {@code true} to continue, {@code false} to stop.
 *
 * @param <T> the type of the input to the predicate
 */
@FunctionalInterface
public interface CheckedPredicate<T> {

    /**
     * Evaluates this predicate on the given argument.
     *
     * @param t the input argument
     * @return {@code true} to continue iteration, {@code false} to stop
     * @throws Exception if the operation fails
     */
    boolean test(T t) throws Exception;
}
