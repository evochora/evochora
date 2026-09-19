/**
 * Handles pointer interaction on the minimap for navigation: mouse, pen or finger.
 * Press to center viewport, drag to pan continuously.
 * Emits 'navigate' events with world coordinates.
 *
 * @class MinimapNavigator
 */
export class MinimapNavigator extends EventTarget {

    /**
     * Creates a new MinimapNavigator.
     *
     * @param {HTMLCanvasElement} canvas - The minimap canvas element.
     * @param {number[]} worldShape - World dimensions [width, height].
     */
    constructor(canvas, worldShape) {
        super();
        this.canvas = canvas;
        this.worldShape = worldShape;
        this.isDragging = false;
        this.onPointerDown = this.onPointerDown.bind(this);
        this.onPointerMove = this.onPointerMove.bind(this);
        this.onPointerUp = this.onPointerUp.bind(this);

        this.bindEvents();
    }

    /**
     * Binds pointer event listeners to the canvas.
     * @private
     */
    bindEvents() {
        this.canvas.addEventListener('pointerdown', this.onPointerDown);
        this.canvas.addEventListener('pointermove', this.onPointerMove);
        this.canvas.addEventListener('pointerup', this.onPointerUp);
        this.canvas.addEventListener('pointercancel', this.onPointerUp);

        // Prevent context menu on right-click
        this.canvas.addEventListener('contextmenu', (e) => e.preventDefault());

        // Set cursor style
        this.canvas.style.cursor = 'crosshair';
    }

    /**
     * Handles pointer down - starts dragging and emits initial navigate event. The canvas keeps the
     * pointer until it is released, so a drag that leaves the minimap goes on to its edge.
     * @param {PointerEvent} e - The pointer event.
     * @private
     */
    onPointerDown(e) {
        if (e.button !== 0) return; // Only the primary button, pen tip or finger
        e.preventDefault();
        this.canvas.setPointerCapture(e.pointerId);
        this.isDragging = true;
        this.emitNavigate(e);
    }

    /**
     * Handles pointer move - emits navigate events while dragging.
     * @param {PointerEvent} e - The pointer event.
     * @private
     */
    onPointerMove(e) {
        if (this.isDragging) {
            this.emitNavigate(e);
        }
    }

    /**
     * Handles pointer up - stops dragging.
     * @private
     */
    onPointerUp() {
        this.isDragging = false;
    }

    /**
     * Converts minimap coordinates to world coordinates and emits navigate event.
     * Uses the same floating-point scale calculation as MinimapAggregator.java on the server.
     * @param {PointerEvent} e - The pointer event.
     * @private
     */
    emitNavigate(e) {
        const rect = this.canvas.getBoundingClientRect();
        const mx = e.clientX - rect.left;
        const my = e.clientY - rect.top;

        // Use the SAME floating-point scale calculation as MinimapAggregator.java:
        // scaleX = worldWidth / minimapWidth (float division)
        // This ensures the entire world is mapped without clipping.
        const scaleX = this.worldShape[0] / this.canvas.width;
        const scaleY = this.worldShape[1] / this.canvas.height;

        // Convert minimap pixel to world cell
        const worldX = mx * scaleX;
        const worldY = my * scaleY;

        this.dispatchEvent(new CustomEvent('navigate', {
            detail: {
                worldX: Math.round(worldX),
                worldY: Math.round(worldY)
            }
        }));
    }

    /**
     * Updates the world shape (call when simulation changes).
     * @param {number[]} worldShape - New world dimensions [width, height].
     */
    updateWorldShape(worldShape) {
        this.worldShape = worldShape;
    }

    /**
     * Cleans up event listeners.
     */
    destroy() {
        this.canvas.removeEventListener('pointerdown', this.onPointerDown);
        this.canvas.removeEventListener('pointermove', this.onPointerMove);
        this.canvas.removeEventListener('pointerup', this.onPointerUp);
        this.canvas.removeEventListener('pointercancel', this.onPointerUp);
    }
}
