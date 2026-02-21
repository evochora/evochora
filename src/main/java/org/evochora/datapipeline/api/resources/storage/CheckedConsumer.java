package org.evochora.datapipeline.api.resources.storage;

/**
 * A consumer that can throw checked exceptions.
 * <p>
 * Used by {@link IBatchStorageRead#forEachChunk} to allow callers to perform operations
 * that may throw checked exceptions (e.g., database writes) in the streaming callback.
 *
 * @param <T> the type of the input to the operation
 */
@FunctionalInterface
public interface CheckedConsumer<T> {

    /**
     * Performs this operation on the given argument.
     *
     * @param t the input argument
     * @throws Exception if the operation fails
     */
    void accept(T t) throws Exception;
}
