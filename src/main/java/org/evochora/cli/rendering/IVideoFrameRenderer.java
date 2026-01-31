package org.evochora.cli.rendering;

import java.awt.image.BufferedImage;
import java.util.List;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.runtime.model.EnvironmentProperties;

/**
 * Interface for video frame renderers.
 * <p>
 * Implementations render simulation data to an image buffer. Each renderer
 * can have its own visual style (exact pixel-per-cell, minimap aggregation, etc.)
 * and define its own CLI options via PicoCLI annotations.
 * <p>
 * <strong>Incremental Rendering:</strong> Use {@link #renderSnapshot(TickData)}
 * for the first frame and {@link #renderDelta(TickDelta)} for subsequent frames.
 * <p>
 * <strong>Thread Safety:</strong> Implementations are not required to be thread-safe.
 * Use one renderer instance per thread.
 */
public interface IVideoFrameRenderer {

    /**
     * Initializes the renderer with environment properties.
     * <p>
     * Must be called after PicoCLI has parsed options and before rendering.
     *
     * @param envProps Environment properties (world shape, topology).
     */
    void init(EnvironmentProperties envProps);

    /**
     * Sets the overlay renderers to apply after each frame.
     * <p>
     * Overlays are applied automatically after rendering each snapshot or delta.
     * Call this after {@link #init(EnvironmentProperties)} and before rendering.
     *
     * @param overlays List of overlay renderers to apply (may be empty).
     */
    void setOverlays(List<IOverlayRenderer> overlays);

    /**
     * Creates a new instance for use in a separate thread.
     * <p>
     * The new instance has the same configuration (CLI options) as this instance
     * and is already initialized with the same EnvironmentProperties.
     * <p>
     * This is needed because renderers are stateful and not thread-safe.
     * Each thread in a multi-threaded rendering pipeline needs its own instance.
     *
     * @return A new, initialized renderer instance with the same configuration.
     */
    IVideoFrameRenderer createThreadInstance();

    /**
     * Renders a snapshot tick, initializing internal state.
     * <p>
     * Call this for the first frame of a video or when starting from a new snapshot.
     *
     * @param snapshot The snapshot tick data containing full environment state.
     * @return The pixel buffer of the rendered frame.
     */
    int[] renderSnapshot(TickData snapshot);

    /**
     * Renders a delta tick incrementally.
     * <p>
     * Only updates changed cells from the delta. Must be called after
     * {@link #renderSnapshot(TickData)} has initialized the internal state.
     *
     * @param delta The delta containing only changed cells since the last sample.
     * @return The pixel buffer of the rendered frame.
     */
    int[] renderDelta(TickDelta delta);

    /**
     * Applies snapshot state WITHOUT rendering.
     * <p>
     * Used for sampling mode optimization where multiple deltas need to be
     * applied before rendering. Call {@link #renderCurrentState()} after
     * applying all deltas to produce the frame.
     *
     * @param snapshot The snapshot tick data containing full environment state.
     */
    void applySnapshotState(TickData snapshot);

    /**
     * Applies delta state WITHOUT rendering.
     * <p>
     * Used for sampling mode optimization where multiple deltas need to be
     * applied before rendering. Call {@link #renderCurrentState()} after
     * applying all deltas to produce the frame.
     *
     * @param delta The delta containing only changed cells.
     */
    void applyDeltaState(TickDelta delta);

    /**
     * Renders the current internal state to pixels.
     * <p>
     * Call after {@link #applySnapshotState(TickData)} and/or
     * {@link #applyDeltaState(TickDelta)} to produce the frame.
     *
     * @return The pixel buffer of the rendered frame.
     */
    int[] renderCurrentState();

    /**
     * Returns the underlying BufferedImage for overlay rendering.
     * <p>
     * Overlays can draw directly onto this image using Graphics2D.
     *
     * @return The frame BufferedImage.
     */
    BufferedImage getFrame();

    /**
     * Returns the output image width in pixels.
     *
     * @return Image width.
     */
    int getImageWidth();

    /**
     * Returns the output image height in pixels.
     *
     * @return Image height.
     */
    int getImageHeight();

    /**
     * Returns a reusable buffer for BGRA pixel data conversion.
     * <p>
     * The buffer is lazily allocated on first use and reused for all subsequent frames.
     * This eliminates per-frame memory allocation, reducing GC pressure.
     *
     * @return Reusable BGRA buffer (width × height × 4 bytes).
     */
    byte[] getBgraBuffer();
}
