package org.evochora.cli.rendering;

import java.awt.image.BufferedImage;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;

/**
 * Interface for overlay renderers.
 * <p>
 * Overlays are drawn on top of the frame after the main renderer has completed.
 * They have access to the full protobuf data (TickData or TickDelta) to extract
 * any information needed for display (statistics, organism details, etc.).
 * <p>
 * <strong>Thread Safety:</strong> Implementations are not required to be thread-safe.
 * Use one overlay instance per thread.
 */
public interface IOverlayRenderer {

    /**
     * Renders overlay for a snapshot frame.
     * <p>
     * Full environment data is available via the TickData parameter.
     *
     * @param frame The frame to render overlay onto.
     * @param snapshot The snapshot tick data with full environment state.
     */
    void render(BufferedImage frame, TickData snapshot);

    /**
     * Renders overlay for a delta frame.
     * <p>
     * Only changed cells are in the delta, but organism state is complete.
     *
     * @param frame The frame to render overlay onto.
     * @param delta The delta tick data.
     */
    void render(BufferedImage frame, TickDelta delta);

    /**
     * Initializes this overlay instance from the original instance.
     * <p>
     * Called by {@link AbstractFrameRenderer#createThreadInstance()} after creating
     * a fresh overlay instance via reflection. Stateful overlays can override this
     * to share accumulated data structures with the original instance.
     * <p>
     * Default implementation does nothing (suitable for stateless overlays).
     *
     * @param original The original overlay instance to initialize from.
     */
    default void initFromOriginal(IOverlayRenderer original) {
        // Default: do nothing — stateless overlays need no initialization
    }
}
