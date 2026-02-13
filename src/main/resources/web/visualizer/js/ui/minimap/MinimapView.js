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
     * @param {function(number): void} onScaleChange - Callback when zoom-out scale is changed.
     */
    constructor(onNavigate, onZoomToggle, onScaleChange) {
        this.onNavigate = onNavigate;
        this.onZoomToggle = onZoomToggle;
        this.onScaleChange = onScaleChange;
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
        this.selectedOrganismId = null; // Selected organism ID for highlight
        this._selectionAnimationId = null; // requestAnimationFrame ID
        this._selectionAnimationStart = 0;
        this.navigator = null; // Initialized when worldShape is set
        this.attachEvents();
    }

    /**
     * Creates the minimap panel DOM elements (collapsed tab + expanded panel).
     * @private
     */
    createDOM() {
        // Collapsed state - tab (with zoom slider)
        this.collapsedElement = document.createElement('div');
        this.collapsedElement.id = 'minimap-panel-collapsed';
        this.collapsedElement.className = 'footer-panel-collapsed hidden';
        this.collapsedElement.innerHTML = `
            <div class="minimap-collapsed-left">
                <span class="panel-label world-size">— × —</span>
            </div>
            <div class="minimap-collapsed-center">
                <input type="range" class="minimap-zoom-slider" min="1" max="11" value="1" title="Zoom level">
            </div>
            <div class="minimap-collapsed-right">
                <span class="panel-arrow minimap-expand-arrow">▲</span>
            </div>
        `;

        // Expanded state - panel (content first, header at bottom)
        this.element = document.createElement('div');
        this.element.id = 'minimap-panel';
        this.element.className = 'footer-panel hidden';
        this.element.innerHTML = `
            <div class="minimap-content"></div>
            <div class="minimap-panel-header">
                <div class="minimap-panel-title">
                    <span class="world-size">— × —</span>
                </div>
                <div class="minimap-panel-center">
                    <input type="range" class="minimap-zoom-slider" min="1" max="11" value="1" title="Zoom level">
                </div>
                <div class="minimap-panel-controls">
                    <button class="minimap-organism-toggle active" title="Toggle organism overlay">Org</button>
                    <button class="panel-toggle" title="Collapse minimap">▼</button>
                </div>
            </div>
        `;

        // Canvas for rendering
        this.canvas = document.createElement('canvas');
        this.canvas.className = 'minimap-canvas';
        this.element.querySelector('.minimap-content').appendChild(this.canvas);

        // Get references
        this.zoomSlider = this.element.querySelector('.minimap-zoom-slider');
        this.zoomSliderCollapsed = this.collapsedElement.querySelector('.minimap-zoom-slider');
        this.collapseBtn = this.element.querySelector('.panel-toggle');
        this.organismToggleBtn = this.element.querySelector('.minimap-organism-toggle');
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
        // Collapsed panel click - expand (except interactive elements)
        this.collapsedElement.addEventListener('click', (e) => {
            if (e.target.closest('.minimap-zoom-slider') ||
                e.target.closest('.minimap-collapsed-center') ||
                e.target.closest('button')) return;
            this.expand();
        });

        // Panel header click - collapse
        this.panelHeader.addEventListener('click', (e) => {
            // Don't collapse if clicking on interactive elements
            if (e.target.closest('button') ||
                e.target.closest('input') ||
                e.target.closest('.minimap-panel-center')) return;
            this.collapse();
        });

        // Collapse button
        this.collapseBtn.addEventListener('click', () => {
            this.collapse();
        });

        // Zoom slider change handler
        const handleZoomSliderChange = (e) => {
            const value = parseInt(e.target.value, 10);
            if (value === 11) {
                // Switch to zoomed-in (detail) mode
                if (this.onZoomToggle) {
                    this.onZoomToggle(false, null);
                }
            } else {
                // Set scale and ensure zoomed-out mode
                if (this.isZoomedOut) {
                    if (this.onScaleChange) {
                        this.onScaleChange(value);
                    }
                } else {
                    if (this.onZoomToggle) {
                        this.onZoomToggle(true, value);
                    }
                }
            }
        };

        // Zoom slider (expanded panel)
        this.zoomSlider.addEventListener('input', handleZoomSliderChange);

        // Zoom slider (collapsed state)
        this.zoomSliderCollapsed.addEventListener('input', (e) => {
            e.stopPropagation();
            handleZoomSliderChange(e);
        });

        // Prevent slider clicks from triggering panel expand/collapse
        this.zoomSlider.addEventListener('click', (e) => e.stopPropagation());
        this.zoomSliderCollapsed.addEventListener('click', (e) => e.stopPropagation());
        this.zoomSlider.addEventListener('mousedown', (e) => e.stopPropagation());
        this.zoomSliderCollapsed.addEventListener('mousedown', (e) => e.stopPropagation());

        // Organism overlay toggle buttons (both panels)
        this.organismToggleBtn.addEventListener('click', () => {
            this.toggleOrganismOverlay();
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

        // Cache background (environment + organisms) for fast viewport updates
        this.renderer.cacheBackground();

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

        if (!this._selectionAnimationId && this.lastMinimapData && this.worldShape) {
            // Restore cached background and redraw only viewport rect (fast path)
            // When selection animation is active, the animation loop handles drawing.
            this.renderer.restoreBackground();
            this.renderer.drawViewportRect(bounds, this.worldShape);
        }

        // Check if minimap is useful (after viewportBounds is set)
        this.updateMinimapUsefulness();
    }

    /**
     * Updates the organism overlay with new organism data.
     * Should be called when organisms are loaded for the current tick.
     *
     * @param {Array} organisms - Array of organism objects with ip, dataPointers, genomeHash
     * @param {function(string): string} [colorResolver] - Maps group key to hex color
     * @param {function(object): string} [keyFn] - Extracts the grouping key from an organism
     */
    updateOrganisms(organisms, colorResolver, keyFn) {
        this.currentOrganisms = organisms;
        this.colorResolver = colorResolver || null;
        this.groupKeyFn = keyFn || null;

        // Re-render if we have minimap data (overlay draws on top of environment)
        if (this.lastMinimapData && this.worldShape) {
            // Re-render environment
            this.renderer.render(this.lastMinimapData);

            // Render organism overlay
            this._renderOrganismOverlay();

            // Update cache with new organism positions
            this.renderer.cacheBackground();

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

        this.organismOverlay.render(ctx, this.currentOrganisms, this.worldShape, canvasSize, this.colorResolver, this.groupKeyFn);
    }

    /**
     * Updates the selected organism ID and starts/stops the pulse animation.
     * @param {string|null} organismId - The selected organism ID, or null to clear
     */
    setSelectedOrganism(organismId) {
        this.selectedOrganismId = organismId;

        if (organismId) {
            this._startSelectionAnimation();
        } else {
            this._stopSelectionAnimation();
            // Restore clean state (no selection ring)
            if (this.lastMinimapData && this.worldShape) {
                this.renderer.restoreBackground();
                if (this.viewportBounds) {
                    this.renderer.drawViewportRect(this.viewportBounds, this.worldShape);
                }
            }
        }
    }

    /**
     * Starts the pulsing selection ring animation loop.
     * @private
     */
    _startSelectionAnimation() {
        if (this._selectionAnimationId) return;
        this._selectionAnimationStart = performance.now();

        const animate = () => {
            this._selectionAnimationId = requestAnimationFrame(animate);

            if (!this.lastMinimapData || !this.worldShape) return;

            const elapsed = performance.now() - this._selectionAnimationStart;
            const phase = (elapsed % 1500) / 1500;

            // Restore cached background (environment + organisms)
            this.renderer.restoreBackground();

            // Draw pulsing selection ring
            if (this.selectedOrganismId && this.currentOrganisms) {
                const selectedOrg = this.currentOrganisms.find(
                    o => String(o.organismId) === this.selectedOrganismId
                );
                if (selectedOrg) {
                    const ctx = this.canvas.getContext('2d');
                    const canvasSize = { width: this.canvas.width, height: this.canvas.height };
                    this.organismOverlay.renderSelection(
                        ctx, selectedOrg, this.worldShape, canvasSize, phase
                    );
                }
            }

            // Draw viewport rectangle on top
            if (this.viewportBounds) {
                this.renderer.drawViewportRect(this.viewportBounds, this.worldShape);
            }
        };

        animate();
    }

    /**
     * Stops the pulsing selection ring animation loop.
     * @private
     */
    _stopSelectionAnimation() {
        if (this._selectionAnimationId) {
            cancelAnimationFrame(this._selectionAnimationId);
            this._selectionAnimationId = null;
        }
    }

    /**
     * Toggles the organism overlay on/off.
     */
    toggleOrganismOverlay() {
        this.setOrganismOverlayEnabled(!this.organismOverlay.isEnabled());
    }

    /**
     * Sets whether the organism overlay is visible.
     * @param {boolean} enabled - True to show organisms, false to hide
     */
    setOrganismOverlayEnabled(enabled) {
        this.organismOverlay.setEnabled(enabled);

        // Update button appearance (both panels)
        if (this.organismToggleBtn) {
            this.organismToggleBtn.classList.toggle('active', enabled);
        }

        // Persist to localStorage
        localStorage.setItem('minimapOrganismOverlay', enabled ? 'true' : 'false');

        // Re-render to apply change
        if (this.lastMinimapData && this.worldShape) {
            this.renderer.render(this.lastMinimapData);
            this._renderOrganismOverlay();
            this.renderer.cacheBackground();
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
     * Updates the zoom slider based on current zoom state and scale.
     * @param {boolean} isZoomedOut - Current zoom state.
     * @param {number} [currentScale=1] - Current zoom-out scale (1-10).
     */
    updateZoomButton(isZoomedOut, currentScale = 1) {
        this.isZoomedOut = isZoomedOut;

        // Slider value: 1-10 for zoomed out scales, 11 for detail mode
        const sliderValue = isZoomedOut ? currentScale : 11;

        if (this.zoomSlider) {
            this.zoomSlider.value = sliderValue;
        }
        if (this.zoomSliderCollapsed) {
            this.zoomSliderCollapsed.value = sliderValue;
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
     * so we hide the minimap content and related controls, but keep the header with zoom slider.
     *
     * IMPORTANT: This method only updates visibility of minimap content and buttons,
     * it does NOT switch between panels to avoid breaking slider drag events.
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
            this._applyMinimapUsefulness();
        }
    }

    /**
     * Applies visibility changes based on minimap usefulness.
     * Only shows/hides minimap content and related buttons, never switches panels.
     * Uses display:none for all elements since slider is now left-aligned and won't shift.
     * @private
     */
    _applyMinimapUsefulness() {
        // Update expand arrow visibility (collapsed panel)
        if (this.expandArrow) {
            this.expandArrow.style.display = this.minimapUseful ? '' : 'none';
        }

        // Update cursor style on collapsed panel (not clickable if not useful)
        if (this.collapsedElement) {
            this.collapsedElement.style.cursor = this.minimapUseful ? 'pointer' : 'default';
        }

        // Show/hide organism toggle and collapse buttons FIRST (before content)
        // so they disappear before the panel shrinks
        if (this.organismToggleBtn) {
            this.organismToggleBtn.style.display = this.minimapUseful ? '' : 'none';
        }
        if (this.collapseBtn) {
            this.collapseBtn.style.display = this.minimapUseful ? '' : 'none';
        }

        // Show/hide minimap content (canvas container) in expanded panel
        const minimapContent = this.element.querySelector('.minimap-content');
        if (minimapContent) {
            minimapContent.style.display = this.minimapUseful ? '' : 'none';
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
            this._applyMinimapUsefulness();
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
            this._applyMinimapUsefulness();
        }
        localStorage.setItem('minimapExpanded', 'false');
    }

    /**
     * Shows the minimap (either collapsed tab or expanded panel).
     * Respects the user's expanded preference and applies usefulness visibility.
     */
    show() {
        this.visible = true;

        // Show panel based on user's expanded preference
        if (this.expanded) {
            this.element.classList.remove('hidden');
            this.collapsedElement.classList.add('hidden');
        } else {
            this.element.classList.add('hidden');
            this.collapsedElement.classList.remove('hidden');
        }

        // Apply usefulness state (hides/shows content within current panel)
        this._applyMinimapUsefulness();
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
     * Restores expanded state and organism overlay state from localStorage.
     */
    restoreState() {
        const expanded = localStorage.getItem('minimapExpanded');
        if (expanded === 'false') {
            this.expanded = false;
        }

        // Restore organism overlay state (default: enabled)
        const organismOverlay = localStorage.getItem('minimapOrganismOverlay');
        if (organismOverlay === 'false') {
            this.organismOverlay.setEnabled(false);
            if (this.organismToggleBtn) {
                this.organismToggleBtn.classList.remove('active');
            }
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
