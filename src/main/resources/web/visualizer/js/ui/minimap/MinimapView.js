import { MinimapRenderer } from './MinimapRenderer.js';
import { MinimapNavigator } from './MinimapNavigator.js';
import { MinimapOrganismOverlay } from './MinimapOrganismOverlay.js';

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
        this.minimapUseful = true; // True when world is larger than viewport

        this.createDOM();
        this.renderer = new MinimapRenderer(this.canvas);
        this.organismOverlay = new MinimapOrganismOverlay();
        this.currentOrganisms = null; // Cached for re-rendering
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
                <span class="panel-label world-size">— × —</span>
            </div>
            <div class="minimap-collapsed-right">
                <button class="minimap-zoom-btn">Zoom In</button>
                <span class="panel-arrow minimap-expand-arrow">▲</span>
            </div>
        `;

        // Expanded state - panel
        this.element = document.createElement('div');
        this.element.id = 'minimap-panel';
        this.element.className = 'footer-panel hidden';
        this.element.innerHTML = `
            <div class="minimap-panel-header">
                <div class="minimap-panel-title">
                    <span class="world-size">— × —</span>
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
        this.worldSizeExpanded = this.element.querySelector('.world-size');
        this.worldSizeCollapsed = this.collapsedElement.querySelector('.world-size');
        this.expandArrow = this.collapsedElement.querySelector('.minimap-expand-arrow');

        document.body.appendChild(this.collapsedElement);
        document.body.appendChild(this.element);
    }

    /**
     * Attaches event listeners.
     * @private
     */
    attachEvents() {
        // Collapsed panel click - expand (except zoom button)
        this.collapsedElement.addEventListener('click', (e) => {
            // Don't expand if clicking on zoom button
            if (e.target.closest('.minimap-zoom-btn')) return;
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

        // Update world size display
        this.updateWorldSizeDisplay(worldShape);

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

        // Render organism overlay (if enabled and we have organisms)
        this._renderOrganismOverlay();

        // Draw viewport rectangle if we have bounds
        if (this.viewportBounds && worldShape) {
            this.renderer.drawViewportRect(this.viewportBounds, worldShape);
        }

        // Sync collapsed panel width with expanded panel
        this.syncPanelWidths();

        // Check if minimap is useful (after worldShape is set)
        this.updateMinimapUsefulness();

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

        // Check if minimap is useful (after viewportBounds is set)
        this.updateMinimapUsefulness();
    }

    /**
     * Updates the organism overlay with new organism data.
     * Should be called when organisms are loaded for the current tick.
     *
     * @param {Array} organisms - Array of organism objects with ip and dataPointers
     */
    updateOrganisms(organisms) {
        this.currentOrganisms = organisms;

        // Re-render if we have minimap data (overlay draws on top of environment)
        if (this.lastMinimapData && this.worldShape) {
            // Re-render environment
            this.renderer.render(this.lastMinimapData);

            // Render organism overlay
            this._renderOrganismOverlay();

            // Re-draw viewport rectangle
            if (this.viewportBounds) {
                this.renderer.drawViewportRect(this.viewportBounds, this.worldShape);
            }
        }
    }

    /**
     * Renders the organism overlay on the minimap canvas.
     * @private
     */
    _renderOrganismOverlay() {
        if (!this.currentOrganisms || !this.worldShape) {
            return;
        }

        const ctx = this.canvas.getContext('2d');
        const canvasSize = {
            width: this.canvas.width,
            height: this.canvas.height
        };

        this.organismOverlay.render(ctx, this.currentOrganisms, this.worldShape, canvasSize);
    }

    /**
     * Sets whether the organism overlay is visible.
     * @param {boolean} enabled - True to show organisms, false to hide
     */
    setOrganismOverlayEnabled(enabled) {
        this.organismOverlay.setEnabled(enabled);

        // Re-render to apply change
        if (this.lastMinimapData && this.worldShape) {
            this.renderer.render(this.lastMinimapData);
            this._renderOrganismOverlay();
            if (this.viewportBounds) {
                this.renderer.drawViewportRect(this.viewportBounds, this.worldShape);
            }
        }
    }

    /**
     * Returns whether the organism overlay is currently enabled.
     * @returns {boolean}
     */
    isOrganismOverlayEnabled() {
        return this.organismOverlay.isEnabled();
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
     * Updates the world size display in both panel states.
     * @param {number[]} worldShape - World dimensions [width, height].
     * @private
     */
    updateWorldSizeDisplay(worldShape) {
        if (!worldShape || worldShape.length < 2) return;
        const text = `${worldShape[0]} × ${worldShape[1]}`;
        if (this.worldSizeExpanded) {
            this.worldSizeExpanded.textContent = text;
        }
        if (this.worldSizeCollapsed) {
            this.worldSizeCollapsed.textContent = text;
        }
    }

    /**
     * Checks if the minimap is useful (world larger than viewport) and updates UI accordingly.
     * When the world fits entirely in the viewport, the minimap provides no navigation value,
     * so we hide the expand functionality and show only the world size + zoom button.
     *
     * IMPORTANT: This does NOT change the `expanded` state, which represents the user's
     * preference. When the minimap becomes useful again, it will restore to the user's
     * preferred state (expanded or collapsed).
     * @private
     */
    updateMinimapUsefulness() {
        // Need both worldShape and viewportBounds to determine usefulness
        if (!this.worldShape || !this.viewportBounds) {
            return;
        }

        const worldWidth = this.worldShape[0];
        const worldHeight = this.worldShape[1];
        const viewportWidth = this.viewportBounds.width;
        const viewportHeight = this.viewportBounds.height;

        // Minimap is useful when world is larger than viewport in any dimension
        const newUseful = viewportWidth < worldWidth || viewportHeight < worldHeight;

        if (newUseful !== this.minimapUseful) {
            this.minimapUseful = newUseful;

            // Update UI based on usefulness
            if (this.expandArrow) {
                this.expandArrow.style.display = this.minimapUseful ? '' : 'none';
            }

            // Update cursor style on collapsed panel (not clickable if not useful)
            if (this.collapsedElement) {
                this.collapsedElement.style.cursor = this.minimapUseful ? 'pointer' : 'default';
            }

            // Re-apply visibility to reflect usefulness change
            // This will restore to user's preferred state (expanded) if minimap became useful again
            if (this.visible) {
                this.show();
            }
        }
    }

    /**
     * Synchronizes the collapsed panel width to match the expanded panel.
     * Measures the expanded panel's actual width for accurate matching.
     * @private
     */
    syncPanelWidths() {
        if (!this.collapsedElement || !this.element) return;

        // Temporarily show expanded panel to measure its width
        const wasHidden = this.element.classList.contains('hidden');
        if (wasHidden) {
            // Briefly show to measure (off-screen measurement trick)
            this.element.style.visibility = 'hidden';
            this.element.classList.remove('hidden');
        }

        // Get the actual rendered width of the expanded panel
        const expandedWidth = this.element.offsetWidth;

        // Restore hidden state if it was hidden
        if (wasHidden) {
            this.element.classList.add('hidden');
            this.element.style.visibility = '';
        }

        // Apply the same width to collapsed panel (uses border-box sizing)
        if (expandedWidth > 0) {
            this.collapsedElement.style.width = `${expandedWidth}px`;
        }
    }

    /**
     * Expands the minimap panel.
     * Does nothing if minimap is not useful (world fits in viewport).
     */
    expand() {
        // Don't expand if minimap is not useful
        if (!this.minimapUseful) {
            return;
        }

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
     * If minimap is not useful, always shows collapsed header only.
     */
    show() {
        this.visible = true;

        // If minimap is not useful, always show collapsed (header-only) view
        if (!this.minimapUseful) {
            this.element.classList.add('hidden');
            this.collapsedElement.classList.remove('hidden');
            return;
        }

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
        this.currentOrganisms = null;
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
        if (this.organismOverlay) {
            this.organismOverlay.destroy();
        }
        if (this.element && this.element.parentNode) {
            this.element.parentNode.removeChild(this.element);
        }
        if (this.collapsedElement && this.collapsedElement.parentNode) {
            this.collapsedElement.parentNode.removeChild(this.collapsedElement);
        }
    }
}
