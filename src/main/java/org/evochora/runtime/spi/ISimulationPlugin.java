package org.evochora.runtime.spi;

/**
 * Base interface for all simulation plugins.
 * <p>
 * This is a marker interface that provides a common base for different plugin types
 * (tick plugins, future instruction plugins, etc.) and enables unified management
 * including serialization for checkpoints.
 * </p>
 * <p>
 * All plugins must implement {@link ISerializable} to support simulation checkpointing
 * and resume. Stateless plugins should return an empty byte array from {@code saveState()}.
 * </p>
 * <p>
 * Implementations must provide a constructor with signature:
 * {@code (IRandomProvider rng, com.typesafe.config.Config options)}
 * </p>
 */
public interface ISimulationPlugin extends ISerializable {
    // Marker interface for common plugin management
}