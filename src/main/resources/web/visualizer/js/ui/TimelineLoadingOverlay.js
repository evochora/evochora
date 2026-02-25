/**
 * Pure rendering class that draws an animated loading overlay on the timeline canvas.
 *
 * Two visual zones relative to the current tick position:
 * - Left (hatching): diagonal stripes moving leftward — "loaded area"
 * - Right (shimmer): a bright stripe sweeping right — "loading ahead"
 *
 * Status text with animated dots is drawn centered on the canvas.
 *
 * This class owns no DOM, no timers, no state management — it only draws
 * when its render() method is called by the owning TickPanelManager.
 *
 * @class TimelineLoadingOverlay
 */
export class TimelineLoadingOverlay {

    /** Diagonal stripe spacing in pixels. */
    static HATCH_SPACING = 8;
    /** Diagonal stripe width in pixels. */
    static HATCH_WIDTH = 2;
    /** Speed of hatching scroll in pixels per second. */
    static HATCH_SPEED = 40;

    /** Duration of one pulse breath cycle in milliseconds. */
    static PULSE_CYCLE_MS = 1000;

    /** Interval between dot animation steps in milliseconds. */
    static DOTS_CYCLE_MS = 500;

    constructor() {
        this._text = '';
    }

    /**
     * Sets the status text shown on the next render.
     * @param {string} text
     */
    setStatusText(text) {
        this._text = text;
    }

    /**
     * Draws the loading overlay on top of the already-rendered timeline.
     *
     * @param {CanvasRenderingContext2D} ctx - The canvas 2D context.
     * @param {number} w - Canvas CSS width.
     * @param {number} h - Canvas CSS height.
     * @param {number} progressX - X position of the current tick (progress boundary).
     * @param {number} timestamp - High-resolution timestamp from requestAnimationFrame.
     */
    render(ctx, w, h, progressX, timestamp) {
        if (w === 0 || h === 0) return;

        const px = Math.round(Math.max(0, Math.min(w, progressX)));

        this._renderHatching(ctx, w, h, px, timestamp);
        this._renderPulse(ctx, w, h, px, timestamp);
        this._renderText(ctx, w, h, timestamp);
    }

    // ── Private ──────────────────────────────────────────────

    /**
     * Draws animated diagonal stripes with a soft glow over the loaded zone [0, progressX].
     */
    _renderHatching(ctx, w, h, px, timestamp) {
        if (px <= 0) return;

        ctx.save();
        ctx.beginPath();
        ctx.rect(0, 0, px, h);
        ctx.clip();

        // Soft glow layer underneath the stripes
        ctx.fillStyle = 'rgba(74, 158, 255, 0.08)';
        ctx.fillRect(0, 0, px, h);

        const spacing = TimelineLoadingOverlay.HATCH_SPACING;
        const lineW = TimelineLoadingOverlay.HATCH_WIDTH;
        const offset = (timestamp / 1000 * TimelineLoadingOverlay.HATCH_SPEED) % spacing;

        ctx.strokeStyle = 'rgba(255, 255, 255, 0.15)';
        ctx.lineWidth = lineW;

        // Diagonal lines at 45° moving leftward
        const diagonal = w + h;
        for (let d = -diagonal + offset; d < diagonal; d += spacing) {
            ctx.beginPath();
            ctx.moveTo(d, 0);
            ctx.lineTo(d - h, h);
            ctx.stroke();
        }

        ctx.restore();
    }

    /**
     * Draws a pulsing glow over the loading zone [progressX, w].
     * Smooth sine-wave breathing, independent of zone width.
     */
    _renderPulse(ctx, w, h, px, timestamp) {
        if (px >= w) return;

        const cycle = TimelineLoadingOverlay.PULSE_CYCLE_MS;
        const t = (timestamp % cycle) / cycle;
        const alpha = 0.12 * (0.5 + 0.5 * Math.sin(t * Math.PI * 2));

        ctx.fillStyle = `rgba(74, 158, 255, ${alpha})`;
        ctx.fillRect(px, 0, w - px, h);
    }

    /**
     * Draws status text with animated dots, centered on the canvas.
     */
    _renderText(ctx, w, h, timestamp) {
        if (!this._text) return;

        const dotCount = Math.floor(timestamp / TimelineLoadingOverlay.DOTS_CYCLE_MS) % 4;
        const dots = '.'.repeat(dotCount);
        const label = this._text + dots;

        ctx.save();
        ctx.font = '11px "Roboto Mono", "Courier New", monospace';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';

        // Shadow for readability against any background
        ctx.fillStyle = 'rgba(0, 0, 0, 0.6)';
        ctx.fillText(label, w / 2 + 1, h / 2 + 1);

        ctx.fillStyle = 'rgba(224, 224, 224, 0.85)';
        ctx.fillText(label, w / 2, h / 2);

        ctx.restore();
    }
}
