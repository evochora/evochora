package org.evochora.cli.rendering;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
import org.evochora.runtime.model.EnvironmentProperties;

import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Abstract base class for video frame renderers.
 * <p>
 * Provides:
 * <ul>
 *   <li>Generic {@link #createThreadInstance()} using reflection to copy PicoCLI options</li>
 *   <li>Automatic overlay application via Template Method pattern</li>
 * </ul>
 * <p>
 * Subclasses must:
 * <ul>
 *   <li>Have a no-argument constructor (for reflection)</li>
 *   <li>Call {@code super.init(envProps)} in their init() override</li>
 *   <li>Implement {@link #doRenderSnapshot(TickData)} and {@link #doRenderDelta(TickDelta)}</li>
 * </ul>
 * <p>
 * <strong>Overlay Handling:</strong> Overlays are applied automatically after each
 * render call. Subclasses do not need to handle overlays - the abstract class does it.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Use {@link #createThreadInstance()} to
 * create separate instances for each thread.
 */
public abstract class AbstractFrameRenderer implements IVideoFrameRenderer, Callable<Integer> {

    /**
     * Shared video rendering options (config, output file, fps, etc.).
     * Populated by PicoCLI from command-line arguments.
     */
    @Mixin
    protected VideoRenderOptions videoOptions;

    /**
     * Environment properties, set by {@link #init(EnvironmentProperties)}.
     * Subclasses can access this after init() has been called.
     */
    protected EnvironmentProperties envProps;

    /**
     * Overlay renderers to apply after each frame.
     */
    private List<IOverlayRenderer> overlays = new ArrayList<>();

    /**
     * Reusable buffer for BGRA conversion. Allocated once per renderer instance
     * to avoid garbage collection pressure during rendering.
     */
    protected byte[] bgraBuffer;

    /**
     * Delta decoder for decompressing ticks from chunks.
     * Used by {@link #renderTick(TickDataChunk, long)} for sampling.
     */
    private DeltaCodec.Decoder decoder;

    /**
     * Initializes the renderer with environment properties.
     * <p>
     * Subclasses that need additional initialization should override this method
     * and call {@code super.init(envProps)} first.
     *
     * @param envProps Environment properties (world shape, topology).
     */
    @Override
    public void init(EnvironmentProperties envProps) {
        this.envProps = envProps;
        this.decoder = new DeltaCodec.Decoder(envProps);
    }

    /**
     * Sets the overlay renderers to apply after each frame.
     * <p>
     * Creates a defensive copy of the list. Overlays are applied in order
     * after each call to {@link #renderSnapshot(TickData)} or {@link #renderDelta(TickDelta)}.
     *
     * @param overlays List of overlay renderers to apply (may be empty or null).
     */
    @Override
    public void setOverlays(List<IOverlayRenderer> overlays) {
        this.overlays = overlays != null ? new ArrayList<>(overlays) : new ArrayList<>();
    }

    /**
     * Renders a snapshot tick and applies overlays.
     * <p>
     * Template method: calls {@link #doRenderSnapshot(TickData)}, then applies all overlays.
     * Subclasses implement the actual rendering in doRenderSnapshot().
     *
     * @param snapshot The snapshot tick data containing full environment state.
     * @return The pixel buffer of the rendered frame (with overlays applied).
     */
    @Override
    public final int[] renderSnapshot(TickData snapshot) {
        int[] pixels = doRenderSnapshot(snapshot);
        applyOverlays(snapshot);
        return pixels;
    }

    /**
     * Renders a delta tick incrementally and applies overlays.
     * <p>
     * Template method: calls {@link #doRenderDelta(TickDelta)}, then applies all overlays.
     * Subclasses implement the actual rendering in doRenderDelta().
     *
     * @param delta The delta containing only changed cells since the last sample.
     * @return The pixel buffer of the rendered frame (with overlays applied).
     */
    @Override
    public final int[] renderDelta(TickDelta delta) {
        int[] pixels = doRenderDelta(delta);
        applyOverlays(delta);
        return pixels;
    }

    /**
     * Renders a specific tick from a chunk using delta decompression.
     * <p>
     * Internally uses {@link DeltaCodec.Decoder} which leverages accumulated
     * deltas as shortcuts for efficiency. The decompressed tick is then
     * rendered via {@link #renderSnapshot(TickData)}.
     *
     * @param chunk The chunk containing the target tick.
     * @param tickNumber The tick number to render.
     * @return The pixel buffer of the rendered frame (with overlays applied).
     * @throws ChunkCorruptedException if the chunk is corrupt or tick not found.
     */
    @Override
    public final int[] renderTick(TickDataChunk chunk, long tickNumber) throws ChunkCorruptedException {
        TickData fullTick = decoder.decompressTick(chunk, tickNumber);
        return renderSnapshot(fullTick);
    }

    /**
     * Renders a snapshot tick. Subclasses implement the actual pixel rendering here.
     * <p>
     * Called by {@link #renderSnapshot(TickData)} before overlays are applied.
     *
     * @param snapshot The snapshot tick data containing full environment state.
     * @return The pixel buffer of the rendered frame.
     */
    protected abstract int[] doRenderSnapshot(TickData snapshot);

    /**
     * Renders a delta tick incrementally. Subclasses implement the actual pixel rendering here.
     * <p>
     * Called by {@link #renderDelta(TickDelta)} before overlays are applied.
     *
     * @param delta The delta containing only changed cells since the last sample.
     * @return The pixel buffer of the rendered frame.
     */
    protected abstract int[] doRenderDelta(TickDelta delta);

    /**
     * Applies all configured overlays to the current frame.
     *
     * @param snapshot The snapshot data for overlay rendering.
     */
    private void applyOverlays(TickData snapshot) {
        for (IOverlayRenderer overlay : overlays) {
            overlay.render(getFrame(), snapshot);
        }
    }

    /**
     * Applies all configured overlays to the current frame.
     *
     * @param delta The delta data for overlay rendering.
     */
    private void applyOverlays(TickDelta delta) {
        for (IOverlayRenderer overlay : overlays) {
            overlay.render(getFrame(), delta);
        }
    }

    /**
     * Returns a reusable buffer for BGRA pixel data conversion.
     * <p>
     * The buffer is lazily allocated on first use and reused for all subsequent frames.
     * Size: width × height × 4 bytes.
     *
     * @return Reusable BGRA buffer.
     */
    public byte[] getBgraBuffer() {
        if (bgraBuffer == null) {
            bgraBuffer = new byte[getImageWidth() * getImageHeight() * 4];
        }
        return bgraBuffer;
    }

    /**
     * Creates a new instance for use in a separate thread.
     * <p>
     * Uses reflection to:
     * <ol>
     *   <li>Create a new instance of the same class</li>
     *   <li>Copy all {@link Option} and {@link Mixin}-annotated fields from this instance and superclasses</li>
     *   <li>Initialize the new instance with the same EnvironmentProperties</li>
     *   <li>Create fresh overlay instances for the new thread</li>
     * </ol>
     *
     * @return A new, initialized renderer instance with the same configuration.
     * @throws IllegalStateException if init() has not been called or instance creation fails.
     */
    @Override
    public IVideoFrameRenderer createThreadInstance() {
        if (envProps == null) {
            throw new IllegalStateException("Cannot create thread instance before init() is called");
        }

        try {
            // Create new instance
            AbstractFrameRenderer copy = getClass().getDeclaredConstructor().newInstance();

            // Copy all @Option and @Mixin fields from this class and superclasses
            Class<?> clazz = getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Option.class) || field.isAnnotationPresent(Mixin.class)) {
                        field.setAccessible(true);
                        field.set(copy, field.get(this));
                    }
                }
                clazz = clazz.getSuperclass();
            }

            // Initialize with same environment
            copy.init(this.envProps);

            // Create fresh overlay instances for this thread
            List<IOverlayRenderer> threadOverlays = new ArrayList<>();
            for (IOverlayRenderer overlay : this.overlays) {
                // Create new instance of the same overlay class
                IOverlayRenderer overlayInstance = overlay.getClass().getDeclaredConstructor().newInstance();
                threadOverlays.add(overlayInstance);
            }
            copy.setOverlays(threadOverlays);

            return copy;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create thread instance: " + e.getMessage(), e);
        }
    }

    /**
     * Executes the video rendering pipeline using this renderer.
     * <p>
     * This method is called by PicoCLI when the renderer subcommand is invoked.
     * It delegates to {@link VideoRenderEngine} which orchestrates the full
     * rendering pipeline (config loading, storage access, ffmpeg, etc.).
     *
     * @return Exit code (0 for success, non-zero for failure).
     * @throws Exception if rendering fails.
     */
    @Override
    public Integer call() throws Exception {
        return new VideoRenderEngine(videoOptions, this).execute();
    }
}
