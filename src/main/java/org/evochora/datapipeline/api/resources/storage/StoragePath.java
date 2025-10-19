package org.evochora.datapipeline.api.resources.storage;

import java.util.Objects;

/**
 * Immutable value object representing a physical storage path.
 * <p>
 * Physical paths include compression extensions (e.g., ".zst") as determined
 * by storage configuration at write time. This design eliminates the need for
 * runtime path resolution and prevents race conditions during concurrent read/write operations.
 * <p>
 * <strong>Key Characteristics:</strong>
 * <ul>
 *   <li>Always represents the actual physical file path including compression extension</li>
 *   <li>Immutable and thread-safe</li>
 *   <li>Suitable for use in collections and as map keys</li>
 *   <li>Value-based equality semantics</li>
 * </ul>
 * <p>
 * <strong>Example paths:</strong>
 * <ul>
 *   <li>{@code "sim123/000/000/batch_0000000000_0000000999.pb.zst"} (with compression)</li>
 *   <li>{@code "sim123/000/000/batch_0000000000_0000000999.pb"} (without compression)</li>
 *   <li>{@code "sim123/metadata.pb.zst"} (metadata with compression)</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> This class is immutable and thread-safe.
 *
 * @see IBatchStorageWrite#writeBatch(java.util.List, long, long)
 * @see IBatchStorageRead#readBatch(StoragePath)
 */
public final class StoragePath {
    
    private final String path;
    
    /**
     * Private constructor to enforce factory method usage.
     *
     * @param path the physical storage path (must not be null or empty)
     * @throws IllegalArgumentException if path is null or empty
     */
    private StoragePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path cannot be null or empty");
        }
        this.path = path;
    }
    
    /**
     * Creates a StoragePath from a string path.
     * <p>
     * The path should be the physical path as returned by storage operations,
     * including any compression extensions.
     *
     * @param path the physical storage path (must not be null or empty)
     * @return a new StoragePath instance
     * @throws IllegalArgumentException if path is null or empty
     */
    public static StoragePath of(String path) {
        return new StoragePath(path);
    }
    
    /**
     * Returns the path as a string.
     * <p>
     * This is the physical path including compression extension.
     *
     * @return the physical storage path
     */
    public String asString() {
        return path;
    }
    
    /**
     * Compares this StoragePath to another object for equality.
     * <p>
     * Two StoragePath instances are equal if they represent the same path string.
     *
     * @param o the object to compare with
     * @return true if the objects are equal, false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StoragePath that = (StoragePath) o;
        return path.equals(that.path);
    }
    
    /**
     * Returns the hash code for this StoragePath.
     *
     * @return the hash code value
     */
    @Override
    public int hashCode() {
        return Objects.hash(path);
    }
    
    /**
     * Returns a string representation of this StoragePath.
     *
     * @return the physical storage path
     */
    @Override
    public String toString() {
        return path;
    }
}

