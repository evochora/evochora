import { ValueFormatter } from '../../utils/ValueFormatter.js';

/**
 * Manages the organism panel list display and organism selection.
 * Handles collapsing/expanding the list while keeping selected organism visible.
 *
 * @class OrganismPanelManager
 */
export class OrganismPanelManager {
    static LIST_STORAGE_KEY = 'evochora-organism-list-collapsed';

    /**
     * Initializes the organism panel manager.
     * @param {object} options - Configuration options
     * @param {HTMLElement} options.panel - The panel element
     * @param {HTMLElement} options.panelHeader - The panel header (clickable to collapse)
     * @param {HTMLElement} options.listContainer - The organism list container
     * @param {HTMLElement} options.listCollapseBtn - Button to collapse/expand the list
     * @param {HTMLElement} options.organismCount - The organism count display in header
     * @param {HTMLElement} options.organismList - The organism list element
     * @param {HTMLElement} options.selectedDisplay - Element showing selected organism when list collapsed
     * @param {HTMLElement} options.filterInput - Filter input element
     * @param {HTMLElement} options.filterClear - Filter clear button element
     * @param {Function} options.onOrganismSelect - Callback when an organism is selected
     * @param {Function} options.onPositionClick - Callback when a position link is clicked (x, y)
     * @param {Function} options.onTickClick - Callback when a tick link is clicked (tick)
     * @param {Function} options.onParentClick - Callback when a parent link is clicked (parentId)
     */
    constructor({
        panel,
        panelHeader,
        listContainer,
        listCollapseBtn,
        organismCount,
        organismList,
        selectedDisplay,
        filterInput,
        filterClear,
        onOrganismSelect,
        onPositionClick,
        onTickClick,
        onParentClick
    }) {
        this.panel = panel;
        this.panelHeader = panelHeader;
        this.listContainer = listContainer;
        this.listCollapseBtn = listCollapseBtn;
        this.organismCount = organismCount;
        this.organismList = organismList;
        this.selectedDisplay = selectedDisplay;
        this.filterInput = filterInput;
        this.filterClear = filterClear;
        this.onOrganismSelect = onOrganismSelect;
        this.onPositionClick = onPositionClick;
        this.onTickClick = onTickClick;
        this.onParentClick = onParentClick;

        // Store current organisms and selection
        this.currentOrganisms = [];
        this.selectedId = null;
        this.filterText = '';

        // Store metadata for accessing organism config (max-entropy, etc.)
        this.metadata = null;

        this.init();
    }
    
    /**
     * Sets the simulation metadata for accessing organism configuration.
     * @param {object|null} metadata - The simulation metadata object
     */
    setMetadata(metadata) {
        this.metadata = metadata;
    }

    /**
     * Initializes the panel state and event listeners.
     * @private
     */
    init() {
        if (!this.panel || !this.listContainer) {
            console.error('OrganismPanelManager: Required elements not found');
            return;
        }

        // Restore list state from localStorage
        const listStored = localStorage.getItem(OrganismPanelManager.LIST_STORAGE_KEY);
        const isListCollapsed = listStored === 'true'; // Default to expanded
        this.setListCollapsed(isListCollapsed);

        // Click on header title area to toggle list
        if (this.panelHeader) {
            this.panelHeader.addEventListener('click', (e) => {
                // Don't toggle if clicking the collapse button itself
                if (e.target.closest('.panel-toggle')) return;
                this.setListCollapsed(!this.isListCollapsed());
            });
        }

        if (this.listCollapseBtn) {
            this.listCollapseBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.setListCollapsed(!this.isListCollapsed());
            });
        }

        // Filter input events
        if (this.filterInput) {
            this.filterInput.addEventListener('input', () => {
                this.filterText = this.filterInput.value;
                this.updateFilterClearButton();
                // Auto-expand when typing
                if (this.filterText && this.isListCollapsed()) {
                    this.setListCollapsed(false);
                }
                this.renderFilteredList();
            });

            // ESC clears the filter
            this.filterInput.addEventListener('keydown', (e) => {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    this.clearFilter();
                    this.filterInput.blur();
                }
            });

            // Prevent header click from toggling when clicking input
            this.filterInput.addEventListener('click', (e) => {
                e.stopPropagation();
            });
        }

        // Filter clear button
        if (this.filterClear) {
            this.filterClear.addEventListener('click', (e) => {
                e.stopPropagation();
                this.clearFilter();
            });
        }
    }

    /**
     * Clears the filter input and re-renders the list.
     */
    clearFilter() {
        this.filterText = '';
        if (this.filterInput) {
            this.filterInput.value = '';
        }
        this.updateFilterClearButton();
        this.renderFilteredList();
    }

    /**
     * Updates the visibility of the filter clear button.
     * @private
     */
    updateFilterClearButton() {
        if (this.filterClear) {
            this.filterClear.classList.toggle('hidden', !this.filterText);
        }
    }

    /**
     * Checks if an organism matches the current filter.
     * Matches: ID, genome hash (partial), or numeric values (ER, IP coords, DP coords).
     * @param {object} org - Organism data
     * @returns {boolean} True if organism matches filter
     * @private
     */
    matchesFilter(org) {
        if (!this.filterText) return true;

        const filter = this.filterText.trim().toLowerCase();
        if (!filter) return true;

        // Exact ID match
        if (org.id === filter || org.id === this.filterText.trim()) return true;

        // Genome hash match (partial, case-insensitive)
        if (org.genomeHash) {
            const genomeLabel = ValueFormatter.formatGenomeHash(org.genomeHash).toLowerCase();
            if (genomeLabel.includes(filter)) return true;
        }

        // Try to parse as number for value matching
        const filterNum = parseInt(filter, 10);
        if (!isNaN(filterNum)) {
            // Match ER (energy)
            if (org.energy === filterNum) return true;

            // Match entropy register
            if (org.entropyRegister === filterNum) return true;

            // Match IP coordinates
            if (org.ip && Array.isArray(org.ip)) {
                if (org.ip.includes(filterNum)) return true;
            }

            // Match DP coordinates
            if (org.dataPointers && Array.isArray(org.dataPointers)) {
                for (const dp of org.dataPointers) {
                    if (dp && Array.isArray(dp) && dp.includes(filterNum)) return true;
                }
            }
        }

        return false;
    }

    /**
     * Renders the filtered organism list.
     * @private
     */
    renderFilteredList() {
        if (!this.organismList) return;

        const filtered = this.currentOrganisms.filter(org => this.matchesFilter(org));

        if (filtered.length === 0 && this.filterText) {
            this.organismList.innerHTML = '<div class="organism-list-empty">No matches</div>';
        } else {
            this.organismList.innerHTML = filtered.map(org => {
                const isSelected = org.id === this.selectedId;
                return this.renderOrganismRow(org, isSelected, isSelected);
            }).join('');
            this.bindRowListeners(this.organismList);

            // Scroll to selected organism if list is visible
            if (this.selectedId && !this.isListCollapsed()) {
                const selectedRow = this.organismList.querySelector('.organism-list-item.selected');
                if (selectedRow) {
                    selectedRow.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
                }
            }
        }

        this.updateSelectedDisplay();
    }

    /**
     * Sets the collapsed state of the organism list.
     * @param {boolean} isCollapsed - True to collapse the list, false to expand
     */
    setListCollapsed(isCollapsed) {
        if (!this.listContainer || !this.listCollapseBtn) return;
        
        this.listContainer.classList.toggle('collapsed', isCollapsed);
        this.listCollapseBtn.textContent = isCollapsed ? '▼' : '▲';
        this.listCollapseBtn.title = isCollapsed ? 'Expand list' : 'Collapse list';
        localStorage.setItem(OrganismPanelManager.LIST_STORAGE_KEY, isCollapsed ? 'true' : 'false');
        
        // Update selected display visibility
        this.updateSelectedDisplay();
    }

    /**
     * Returns whether the organism list is currently collapsed.
     * @returns {boolean}
     */
    isListCollapsed() {
        return this.listContainer?.classList.contains('collapsed') ?? false;
    }

    /**
     * Updates the organism count display.
     * @param {number} alive - Number of alive organisms
     * @param {number} total - Total number of organisms
     */
    updateInfo(alive, total) {
        const text = `(${alive}/${total})`;
        if (this.organismCount) this.organismCount.textContent = text;
    }

    /**
     * Renders a single organism row HTML.
     * @param {object} org - Organism data
     * @param {boolean} isSelected - Whether this organism is selected
     * @param {boolean} showDeselect - Whether to show deselect button
     * @returns {string} HTML string
     * @private
     */
    renderOrganismRow(org, isSelected, showDeselect = false) {
        // Format SR (entropy register) with percentage if max-entropy is available
        const srValue = org.entropyRegister != null ? org.entropyRegister : 0;
        let srDisplay;
        try {
            const config = this.metadata?.resolvedConfigJson
                ? JSON.parse(this.metadata.resolvedConfigJson)
                : null;
            const maxEntropy = config?.runtime?.organism?.["max-entropy"];
            if (maxEntropy != null && maxEntropy > 0) {
                const srPercent = String(Math.round((srValue / maxEntropy) * 100)).padStart(3);
                const srValuePadded = String(srValue).padStart(5);
                srDisplay = `${srPercent}% ${srValuePadded}`;
            } else {
                srDisplay = String(srValue);
            }
        } catch (e) {
            srDisplay = String(srValue);
        }
        
        // Format IP with direction arrow
        let ipDisplay = '-';
        if (org.ip && Array.isArray(org.ip) && org.ip.length >= 2) {
            const ipX = org.ip[0];
            const ipY = org.ip[1];
            const dvArrow = org.dv ? ValueFormatter.formatDvAsArrow(org.dv) : '';
            ipDisplay = `<span class="clickable-position" data-x="${ipX}" data-y="${ipY}">${ipX}|${ipY}</span>${dvArrow}`;
        }
        
        // Format DPs separately
        const activeDpIndex = org.activeDpIndex != null ? org.activeDpIndex : -1;
        
        const formatDp = (dp, index) => {
            if (!dp || !Array.isArray(dp) || dp.length < 2) return '-';
            const dpX = dp[0];
            const dpY = dp[1];
            const isActive = (index === activeDpIndex);
            // Active: [x|y], Inactive: space before and after for alignment
            const dpText = isActive ? `[${dpX}|${dpY}]` : ` ${dpX}|${dpY} `;
            return `<span class="clickable-position" data-x="${dpX}" data-y="${dpY}">${dpText}</span>`;
        };
        
        const dp0Display = org.dataPointers?.[0] ? formatDp(org.dataPointers[0], 0) : '-';
        const dp1Display = org.dataPointers?.[1] ? formatDp(org.dataPointers[1], 1) : '-';
        
        const deselectBtn = showDeselect ? 
            `<span class="organism-col organism-col-deselect"><button class="organism-deselect" title="Deselect">✕</button></span>` : '';
        
        const genomeDisplay = ValueFormatter.formatGenomeHash(org.genomeHash);

        return `
            <div class="organism-list-item ${isSelected ? 'selected' : ''}"
                 data-organism-id="${org.id}">
                <span class="organism-col organism-col-id" style="color: ${org.color}">#${org.id}</span>
                <span class="organism-col organism-col-genome">${genomeDisplay}</span>
                <span class="organism-col organism-col-er">ER:${org.energy}</span>
                <span class="organism-col organism-col-sr">SR:${srDisplay}</span>
                <span class="organism-col organism-col-ip"><span class="organism-label">IP:</span>${ipDisplay}</span>
                <span class="organism-col organism-col-dps"><span class="organism-label">DPs:</span></span>
                <span class="organism-col organism-col-dp0">${dp0Display}</span>
                <span class="organism-col organism-col-dp1">${dp1Display}</span>
                ${deselectBtn}
            </div>
        `;
    }

    /**
     * Binds click listeners to organism row elements.
     * @param {HTMLElement} container - Container with organism rows
     * @private
     */
    bindRowListeners(container) {
        // Click on row to select/deselect
        container.querySelectorAll('.organism-list-item').forEach(item => {
            item.addEventListener('click', (e) => {
                // Ignore clicks on links or deselect button
                if (e.target.classList.contains('clickable-position') || 
                    e.target.classList.contains('clickable-parent') ||
                    e.target.classList.contains('clickable-tick') ||
                    e.target.classList.contains('organism-deselect')) {
                    return;
                }
                const id = item.dataset.organismId;
                const isSelected = item.classList.contains('selected');
                if (this.onOrganismSelect) {
                    this.onOrganismSelect(isSelected ? null : id);
                }
            });
        });
        
        // Deselect button
        container.querySelectorAll('.organism-deselect').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                if (this.onOrganismSelect) {
                    this.onOrganismSelect(null);
                }
            });
        });
        
        // Position links
        container.querySelectorAll('.clickable-position').forEach(el => {
            el.addEventListener('click', (e) => {
                e.stopPropagation();
                const x = parseInt(el.dataset.x, 10);
                const y = parseInt(el.dataset.y, 10);
                if (!isNaN(x) && !isNaN(y) && this.onPositionClick) {
                    this.onPositionClick(x, y);
                }
            });
        });
        
        // Parent links
        container.querySelectorAll('.clickable-parent').forEach(el => {
            el.addEventListener('click', (e) => {
                e.stopPropagation();
                const parentId = el.dataset.parentId;
                if (parentId && this.onParentClick) {
                    this.onParentClick(parentId);
                }
            });
        });
        
        // Tick links
        container.querySelectorAll('.clickable-tick').forEach(el => {
            el.addEventListener('click', (e) => {
                e.stopPropagation();
                const tick = parseInt(el.dataset.tick, 10);
                if (!isNaN(tick) && this.onTickClick) {
                    this.onTickClick(tick);
                }
            });
        });
    }

    /**
     * Updates the organism list with new data.
     * @param {Array<object>} organisms - Array of organism data with all summary fields
     * @param {string|null} selectedId - Currently selected organism ID
     */
    updateList(organisms, selectedId) {
        this.currentOrganisms = organisms;
        this.selectedId = selectedId;

        // Use filtered rendering
        this.renderFilteredList();
    }

    /**
     * Updates the selection state in the organism list.
     * @param {string|null} selectedId - Currently selected organism ID
     */
    updateSelection(selectedId) {
        this.selectedId = selectedId;
        // Re-render with filter applied
        this.renderFilteredList();
    }

    /**
     * Updates the selected organism display (shown when list is collapsed).
     * @private
     */
    updateSelectedDisplay() {
        if (!this.selectedDisplay) return;
        
        const isCollapsed = this.isListCollapsed();
        const selectedOrg = this.selectedId ? 
            this.currentOrganisms.find(o => o.id === this.selectedId) : null;
        
        if (isCollapsed && selectedOrg) {
            // Show selected organism when list is collapsed
            this.selectedDisplay.innerHTML = this.renderOrganismRow(selectedOrg, true, true);
            this.bindRowListeners(this.selectedDisplay);
            this.selectedDisplay.style.display = '';
        } else {
            this.selectedDisplay.innerHTML = '';
            this.selectedDisplay.style.display = 'none';
        }
    }
}

