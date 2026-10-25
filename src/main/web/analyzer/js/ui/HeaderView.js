/**
 * Header View Component
 * 
 * Manages the header bar with its loading indicator. The run is chosen in the footer, the
 * metric groups are listed by the dashboard view, and a card reloads itself.
 * 
 * @module HeaderView
 */
    
    // DOM elements
    let loadingIndicator = null;

    /**
     * Initializes the header view.
     */
export function init() {
        loadingIndicator = document.getElementById('loading-indicator');
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
