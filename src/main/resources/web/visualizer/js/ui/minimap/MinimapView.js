import { MinimapRenderer } from './MinimapRenderer.js';
import { MinimapNavigator } from './MinimapNavigator.js';

/**
 * Orchestrates minimap rendering and navigation.
 * Creates DOM elements, connects renderer and navigator, and handles
 * bidirectional synchronization with the main environment grid.
 *
 * @class MinimapView
 */
export class MinimapView {

    /**
     * Creates a new MinimapView.
     *
     * @param {HTMLElement} container - Container element (typically .world-container).
     * @param {function(number, number): void} onNavigate - Callback when user navigates via minimap.
     */
    constructor(container, onNavigate) {
        this.container = container;
        this.onNavigate = onNavigate;
        this.worldShape = null;
        this.lastMinimapData = null;
        this.viewportBounds = null;
        this.visible = false;

        this.createDOM();
        this.renderer = new MinimapRenderer(this.canvas);
        this.navigator = null; // Initialized when worldShape is set
    }

    /**
     * Creates the minimap DOM elements.
     * @private
     */
    createDOM() {
        if (!this.container) {
            console.error('[MinimapView] Container element is null - cannot create minimap');
            return;
        }

        // Container div for positioning and styling
        this.element = document.createElement('div');
        this.element.className = 'minimap-container';
        this.element.style.display = 'none'; // Hidden until data arrives

        // Canvas for rendering
        this.canvas = document.createElement('canvas');
        this.canvas.className = 'minimap-canvas';

        this.element.appendChild(this.canvas);
        this.container.appendChild(this.element);
    }

    /**
     * Updates the minimap with new data from the server.
     * Should be called when environment data is loaded with minimap flag.
     *
     * @param {{width: number, height: number, cellTypes: Uint8Array}} minimapData - Minimap data.
     * @param {number[]} worldShape - World dimensions [width, height].
     */
    update(minimapData, worldShape) {
        if (!minimapData) {
            return;
        }

        this.worldShape = worldShape;
        this.lastMinimapData = minimapData;

        // Initialize navigator on first update
        if (!this.navigator && worldShape) {
            this.navigator = new MinimapNavigator(this.canvas, worldShape);
            this.navigator.addEventListener('navigate', (e) => {
                if (this.onNavigate) {
                    this.onNavigate(e.detail.worldX, e.detail.worldY);
                }
            });
        } else if (this.navigator && worldShape) {
            this.navigator.updateWorldShape(worldShape);
        }

        // Render minimap
        this.renderer.render(minimapData);

        // Draw viewport rectangle if we have bounds
        if (this.viewportBounds && worldShape) {
            this.renderer.drawViewportRect(this.viewportBounds, worldShape);
        }

        // Show the minimap
        this.show();
    }

    /**
     * Updates the viewport rectangle position.
     * Called when the main grid viewport changes (pan, zoom).
     *
     * @param {{x: number, y: number, width: number, height: number}} bounds - Viewport in world coordinates.
     */
    updateViewport(bounds) {
        this.viewportBounds = bounds;

        if (this.lastMinimapData && this.worldShape) {
            this.renderer.updateViewportRect(bounds, this.worldShape);
        }
    }

    /**
     * Shows the minimap.
     */
    show() {
        if (!this.element) {
            return;
        }
        if (!this.visible) {
            this.element.style.display = 'block';
            this.visible = true;
        }
    }

    /**
     * Hides the minimap.
     */
    hide() {
        if (this.visible) {
            this.element.style.display = 'none';
            this.visible = false;
        }
    }

    /**
     * Clears the minimap state (e.g., when changing runs).
     */
    clear() {
        this.lastMinimapData = null;
        this.viewportBounds = null;
        this.hide();
    }

    /**
     * Cleans up resources.
     */
    destroy() {
        if (this.navigator) {
            this.navigator.destroy();
        }
        if (this.element && this.element.parentNode) {
            this.element.parentNode.removeChild(this.element);
        }
    }
}
