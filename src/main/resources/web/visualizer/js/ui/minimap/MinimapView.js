import { MinimapRenderer } from './MinimapRenderer.js';
import { MinimapNavigator } from './MinimapNavigator.js';

/**
 * Orchestrates minimap rendering and navigation as a collapsible panel.
 * Creates DOM elements similar to header panels but positioned at the bottom.
 *
 * @class MinimapView
 */
export class MinimapView {

    /**
     * Creates a new MinimapView.
     *
     * @param {function(number, number): void} onNavigate - Callback when user navigates via minimap.
     * @param {function(boolean): void} onZoomToggle - Callback when zoom button is clicked.
     */
    constructor(onNavigate, onZoomToggle) {
        this.onNavigate = onNavigate;
        this.onZoomToggle = onZoomToggle;
        this.worldShape = null;
        this.lastMinimapData = null;
        this.viewportBounds = null;
        this.expanded = true;
        this.visible = false;
        this.isZoomedOut = true;

        this.createDOM();
        this.renderer = new MinimapRenderer(this.canvas);
        this.navigator = null; // Initialized when worldShape is set
        this.attachEvents();
    }

    /**
     * Creates the minimap panel DOM elements (collapsed tab + expanded panel).
     * @private
     */
    createDOM() {
        // Collapsed state - tab (same width as expanded, with zoom button)
        this.collapsedElement = document.createElement('div');
        this.collapsedElement.id = 'minimap-panel-collapsed';
        this.collapsedElement.className = 'footer-panel-collapsed hidden';
        this.collapsedElement.innerHTML = `
            <div class="minimap-collapsed-left">
                <span class="panel-icon">🗺</span>
                <span class="panel-label">Minimap</span>
            </div>
            <div class="minimap-collapsed-right">
                <button class="minimap-zoom-btn">Zoom In</button>
                <span class="panel-arrow">▲</span>
            </div>
        `;

        // Expanded state - panel
        this.element = document.createElement('div');
        this.element.id = 'minimap-panel';
        this.element.className = 'footer-panel hidden';
        this.element.innerHTML = `
            <div class="minimap-panel-header">
                <div class="minimap-panel-title">
                    <span class="panel-icon">🗺</span>
                    <span>Minimap</span>
                </div>
                <div class="minimap-panel-controls">
                    <button class="minimap-zoom-btn">Zoom In</button>
                    <button class="panel-toggle" title="Collapse minimap">▼</button>
                </div>
            </div>
            <div class="minimap-content"></div>
        `;

        // Canvas for rendering
        this.canvas = document.createElement('canvas');
        this.canvas.className = 'minimap-canvas';
        this.element.querySelector('.minimap-content').appendChild(this.canvas);

        // Get references
        this.zoomBtn = this.element.querySelector('.minimap-zoom-btn');
        this.zoomBtnCollapsed = this.collapsedElement.querySelector('.minimap-zoom-btn');
        this.collapseBtn = this.element.querySelector('.panel-toggle');
        this.panelHeader = this.element.querySelector('.minimap-panel-header');
        this.collapsedLeft = this.collapsedElement.querySelector('.minimap-collapsed-left');

        document.body.appendChild(this.collapsedElement);
        document.body.appendChild(this.element);
    }

    /**
     * Attaches event listeners.
     * @private
     */
    attachEvents() {
        // Collapsed tab left part click - expand panel
        this.collapsedLeft.addEventListener('click', () => {
            this.expand();
        });

        // Collapsed arrow click - expand panel
        this.collapsedElement.querySelector('.panel-arrow').addEventListener('click', () => {
            this.expand();
        });

        // Panel header click - collapse
        this.panelHeader.addEventListener('click', (e) => {
            // Don't collapse if clicking on buttons
            if (e.target.closest('button')) return;
            this.collapse();
        });

        // Collapse button
        this.collapseBtn.addEventListener('click', () => {
            this.collapse();
        });

        // Zoom button (expanded panel)
        this.zoomBtn.addEventListener('click', () => {
            if (this.onZoomToggle) {
                this.onZoomToggle(!this.isZoomedOut);
            }
        });

        // Zoom button (collapsed state)
        this.zoomBtnCollapsed.addEventListener('click', (e) => {
            e.stopPropagation(); // Don't trigger expand
            if (this.onZoomToggle) {
                this.onZoomToggle(!this.isZoomedOut);
            }
        });
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
     * Updates both zoom buttons (expanded and collapsed) based on current zoom state.
     * @param {boolean} isZoomedOut - Current zoom state.
     */
    updateZoomButton(isZoomedOut) {
        this.isZoomedOut = isZoomedOut;
        const text = isZoomedOut ? 'Zoom In' : 'Zoom Out';
        if (this.zoomBtn) {
            this.zoomBtn.textContent = text;
        }
        if (this.zoomBtnCollapsed) {
            this.zoomBtnCollapsed.textContent = text;
        }
    }

    /**
     * Expands the minimap panel.
     */
    expand() {
        this.expanded = true;
        this.collapsedElement.classList.add('hidden');
        if (this.visible) {
            this.element.classList.remove('hidden');
        }
        localStorage.setItem('minimapExpanded', 'true');
    }

    /**
     * Collapses the minimap panel to just the tab.
     */
    collapse() {
        this.expanded = false;
        this.element.classList.add('hidden');
        if (this.visible) {
            this.collapsedElement.classList.remove('hidden');
        }
        localStorage.setItem('minimapExpanded', 'false');
    }

    /**
     * Shows the minimap (either collapsed tab or expanded panel).
     */
    show() {
        this.visible = true;
        if (this.expanded) {
            this.element.classList.remove('hidden');
            this.collapsedElement.classList.add('hidden');
        } else {
            this.element.classList.add('hidden');
            this.collapsedElement.classList.remove('hidden');
        }
    }

    /**
     * Hides the minimap completely.
     */
    hide() {
        this.visible = false;
        this.element.classList.add('hidden');
        this.collapsedElement.classList.add('hidden');
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
     * Restores expanded state from localStorage.
     */
    restoreState() {
        const expanded = localStorage.getItem('minimapExpanded');
        if (expanded === 'false') {
            this.expanded = false;
        }
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
        if (this.collapsedElement && this.collapsedElement.parentNode) {
            this.collapsedElement.parentNode.removeChild(this.collapsedElement);
        }
    }
}
