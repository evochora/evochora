/**
 * Handles mouse interaction on the minimap for navigation.
 * Click to center viewport, drag to pan continuously.
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

        this.bindEvents();
    }

    /**
     * Binds mouse event listeners to the canvas.
     * @private
     */
    bindEvents() {
        this.canvas.addEventListener('mousedown', this.onMouseDown.bind(this));
        this.canvas.addEventListener('mousemove', this.onMouseMove.bind(this));
        this.canvas.addEventListener('mouseup', this.onMouseUp.bind(this));
        this.canvas.addEventListener('mouseleave', this.onMouseUp.bind(this));

        // Prevent context menu on right-click
        this.canvas.addEventListener('contextmenu', (e) => e.preventDefault());

        // Set cursor style
        this.canvas.style.cursor = 'crosshair';
    }

    /**
     * Handles mouse down - starts dragging and emits initial navigate event.
     * @param {MouseEvent} e - The mouse event.
     * @private
     */
    onMouseDown(e) {
        if (e.button !== 0) return; // Only left click
        this.isDragging = true;
        this.emitNavigate(e);
    }

    /**
     * Handles mouse move - emits navigate events while dragging.
     * @param {MouseEvent} e - The mouse event.
     * @private
     */
    onMouseMove(e) {
        if (this.isDragging) {
            this.emitNavigate(e);
        }
    }

    /**
     * Handles mouse up - stops dragging.
     * @private
     */
    onMouseUp() {
        this.isDragging = false;
    }

    /**
     * Converts minimap coordinates to world coordinates and emits navigate event.
     * @param {MouseEvent} e - The mouse event.
     * @private
     */
    emitNavigate(e) {
        const rect = this.canvas.getBoundingClientRect();
        const mx = e.clientX - rect.left;
        const my = e.clientY - rect.top;

        // Minimap coordinates (0 to canvas.width/height) → World coordinates
        const worldX = (mx / this.canvas.width) * this.worldShape[0];
        const worldY = (my / this.canvas.height) * this.worldShape[1];

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
        this.canvas.removeEventListener('mousedown', this.onMouseDown);
        this.canvas.removeEventListener('mousemove', this.onMouseMove);
        this.canvas.removeEventListener('mouseup', this.onMouseUp);
        this.canvas.removeEventListener('mouseleave', this.onMouseUp);
    }
}
