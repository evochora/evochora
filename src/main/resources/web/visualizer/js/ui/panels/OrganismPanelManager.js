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
        this.onOrganismSelect = onOrganismSelect;
        this.onPositionClick = onPositionClick;
        this.onTickClick = onTickClick;
        this.onParentClick = onParentClick;
        
        // Store current organisms and selection
        this.currentOrganisms = [];
        this.selectedId = null;

        this.init();
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
        // Format SR (entropy register) with percentage (aligned: "  0%     0" to "100% 32767")
        const MAX_ENTROPY = 32767/4; // TODO: needs to be stored in metadata and used from there!
        const srValue = org.entropyRegister != null ? org.entropyRegister : 0;
        const srPercent = String(Math.round((srValue / MAX_ENTROPY) * 100)).padStart(3);
        const srValuePadded = String(srValue).padStart(5);
        const srDisplay = `${srPercent}% ${srValuePadded}`;
        
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
        
        return `
            <div class="organism-list-item ${isSelected ? 'selected' : ''}" 
                 data-organism-id="${org.id}">
                <span class="organism-col organism-col-id" style="color: ${org.color}">#${org.id}</span>
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
        
        if (!this.organismList) return;

        // Render all organisms, selected one gets deselect button
        this.organismList.innerHTML = organisms.map(org => {
            const isSelected = org.id === selectedId;
            return this.renderOrganismRow(org, isSelected, isSelected);
        }).join('');

        this.bindRowListeners(this.organismList);
        this.updateSelectedDisplay();
    }

    /**
     * Updates the selection state in the organism list.
     * @param {string|null} selectedId - Currently selected organism ID
     */
    updateSelection(selectedId) {
        this.selectedId = selectedId;
        
        if (!this.organismList) return;

        this.organismList.querySelectorAll('.organism-list-item').forEach(item => {
            const isSelected = item.dataset.organismId === selectedId;
            item.classList.toggle('selected', isSelected);
            
            // Add/remove deselect button
            const deselectCol = item.querySelector('.organism-col-deselect');
            if (isSelected && !deselectCol) {
                const btn = document.createElement('span');
                btn.className = 'organism-col organism-col-deselect';
                btn.innerHTML = '<button class="organism-deselect" title="Deselect">✕</button>';
                btn.querySelector('button').addEventListener('click', (e) => {
                    e.stopPropagation();
                    if (this.onOrganismSelect) this.onOrganismSelect(null);
                });
                item.appendChild(btn);
            } else if (!isSelected && deselectCol) {
                deselectCol.remove();
            }
        });
        
        this.updateSelectedDisplay();
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

