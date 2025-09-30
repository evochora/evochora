package org.evochora.runtime.spi;

/**
 * Interface for components that support state serialization for checkpointing.
 * <p>
 * All pluggable simulation components (random providers, strategies, etc.) should
 * implement this interface to enable simulation resume from checkpoints. The
 * serialized state must be complete enough to restore the component to its exact
 * state at the time of serialization.
 * </p>
 * <p>
 * Implementations should use efficient binary serialization (e.g., ByteBuffer) and
 * ensure deterministic serialization for reproducibility.
 * </p>
 */
public interface ISerializable {

    /**
     * Serializes the complete internal state of this component.
     * <p>
     * For stateless components, this should return an empty byte array.
     * For stateful components, this must include ALL state required to restore
     * the component to its exact current state.
     * </p>
     *
     * @return Byte array containing the complete internal state, or empty array if stateless.
     */
    byte[] saveState();

    /**
     * Restores the internal state of this component from previously saved state.
     * <p>
     * This method must restore the component to the exact state it was in when
     * saveState() was called. For stateless components, this is a no-op.
     * </p>
     *
     * @param state The state bytes previously returned by saveState()
     * @throws IllegalArgumentException if state is null, invalid, or incompatible with this component
     */
    void loadState(byte[] state);
}