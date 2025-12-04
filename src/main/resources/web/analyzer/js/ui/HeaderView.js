/**
 * Header View Component
 * 
 * Manages the header bar with run selection dropdown and refresh button.
 * 
 * @module HeaderView
 */

const HeaderView = (function() {
    'use strict';
    
    // DOM elements
    let runSelect = null;
    let refreshBtn = null;
    let loadingIndicator = null;
    
    // State
    let onRunChange = null;
    let onRefresh = null;
    
    /**
     * Initializes the header view.
     * 
     * @param {Object} callbacks - Event callbacks
     * @param {Function} callbacks.onRunChange - Called when run selection changes
     * @param {Function} callbacks.onRefresh - Called when refresh button clicked
     */
    function init(callbacks) {
        runSelect = document.getElementById('run-select');
        refreshBtn = document.getElementById('btn-refresh');
        loadingIndicator = document.getElementById('loading-indicator');
        
        onRunChange = callbacks.onRunChange || (() => {});
        onRefresh = callbacks.onRefresh || (() => {});
        
        // Event listeners
        runSelect.addEventListener('change', handleRunChange);
        refreshBtn.addEventListener('click', handleRefresh);
        
        console.debug('[HeaderView] Initialized');
    }
    
    /**
     * Handles run selection change.
     */
    function handleRunChange() {
        const runId = runSelect.value;
        if (runId) {
            onRunChange(runId);
        }
    }
    
    /**
     * Handles refresh button click.
     */
    function handleRefresh() {
        onRefresh();
    }
    
    /**
     * Populates the run dropdown with available runs.
     * 
     * @param {Array<{runId: string, startTime?: number}>} runs - Available runs
     * @param {string} selectedRunId - Currently selected run ID (optional)
     */
    function setRuns(runs, selectedRunId = null) {
        runSelect.innerHTML = '';
        
        if (runs.length === 0) {
            const option = document.createElement('option');
            option.value = '';
            option.textContent = 'No runs available';
            option.disabled = true;
            option.selected = true;
            runSelect.appendChild(option);
            return;
        }
        
        // Sort by start time (newest first)
        const sorted = [...runs].sort((a, b) => {
            return (b.startTime || 0) - (a.startTime || 0);
        });
        
        sorted.forEach(run => {
            const option = document.createElement('option');
            option.value = run.runId;
            option.textContent = formatRunLabel(run);
            if (run.runId === selectedRunId) {
                option.selected = true;
            }
            runSelect.appendChild(option);
        });
        
        // Select first if none specified
        if (!selectedRunId && sorted.length > 0) {
            runSelect.value = sorted[0].runId;
        }
    }
    
    /**
     * Formats a run for display in the dropdown.
     */
    function formatRunLabel(run) {
        if (run.startTime) {
            const date = new Date(run.startTime);
            return `${run.runId.substring(0, 20)}... (${date.toLocaleString()})`;
        }
        return run.runId.substring(0, 30) + (run.runId.length > 30 ? '...' : '');
    }
    
    /**
     * Gets the currently selected run ID.
     * 
     * @returns {string|null}
     */
    function getSelectedRunId() {
        return runSelect.value || null;
    }
    
    /**
     * Shows or hides the loading indicator.
     * 
     * @param {boolean} show
     */
    function setLoading(show) {
        if (loadingIndicator) {
            loadingIndicator.classList.toggle('active', show);
        }
        if (refreshBtn) {
            refreshBtn.classList.toggle('loading', show);
            refreshBtn.disabled = show;
        }
    }
    
    /**
     * Updates the logo text width for loading animation.
     */
    function updateLogoWidth() {
        const logoText = document.querySelector('.logo-text');
        if (logoText && loadingIndicator) {
            const width = logoText.offsetWidth;
            loadingIndicator.style.setProperty('--logo-text-width', `${width}px`);
        }
    }
    
    // Public API
    return {
        init,
        setRuns,
        getSelectedRunId,
        setLoading,
        updateLogoWidth
    };
    
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = HeaderView;
}

