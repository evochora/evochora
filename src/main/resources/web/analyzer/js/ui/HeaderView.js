/**
 * Header View Component
 * 
 * Manages the header bar with refresh button and loading indicator.
 * Run selection has moved to the footer component.
 * 
 * @module HeaderView
 */

// DOM elements
let refreshBtn = null;
let loadingIndicator = null;

// State
let onRefresh = null;

/**
 * Initializes the header view.
 * 
 * @param {Object} callbacks - Event callbacks
 * @param {Function} callbacks.onRefresh - Called when refresh button clicked
 */
export function init(callbacks) {
    refreshBtn = document.getElementById('btn-refresh');
    loadingIndicator = document.getElementById('loading-indicator');
    
    onRefresh = callbacks.onRefresh || (() => {});
    
    // Event listeners
    if (refreshBtn) {
        refreshBtn.addEventListener('click', handleRefresh);
    }
    
    console.debug('[HeaderView] Initialized');
}

/**
 * Handles refresh button click.
 */
function handleRefresh() {
    onRefresh();
}

/**
 * Shows or hides the loading indicator.
 * 
 * @param {boolean} show
 */
export function setLoading(show) {
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
export function updateLogoWidth() {
    const logoText = document.querySelector('.logo-text');
    if (logoText && loadingIndicator) {
        const width = logoText.offsetWidth;
        loadingIndicator.style.setProperty('--logo-text-width', `${width}px`);
    }
}
