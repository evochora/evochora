/**
 * Dashboard View Component
 * 
 * Manages the grid of metric cards.
 * 
 * @module DashboardView
 */

const DashboardView = (function() {
    'use strict';
    
    // DOM elements
    let container = null;
    let dashboardGrid = null;
    
    // State
    let metricCards = {}; // metricId -> MetricCard instance
    
    /**
     * Initializes the dashboard view.
     */
    function init() {
        container = document.getElementById('dashboard-container');
        
        // Create dashboard grid
        dashboardGrid = document.createElement('div');
        dashboardGrid.className = 'dashboard';
        dashboardGrid.id = 'dashboard-grid';
        container.appendChild(dashboardGrid);
        
        console.debug('[DashboardView] Initialized');
    }
    
    /**
     * Clears all metric cards from the dashboard.
     */
    function clear() {
        Object.values(metricCards).forEach(card => {
            MetricCardView.destroy(card);
        });
        metricCards = {};
        dashboardGrid.innerHTML = '';
    }
    
    /**
     * Creates metric cards for all metrics in manifest.
     * 
     * @param {Array<Object>} metrics - Array of ManifestEntry objects
     */
    function createCards(metrics) {
        clear();
        
        if (!metrics || metrics.length === 0) {
            showEmptyState('No metrics available for this run.');
            return;
        }
        
        metrics.forEach(metric => {
            const card = MetricCardView.create(metric);
            metricCards[metric.id] = card;
            dashboardGrid.appendChild(card.element);
        });
    }
    
    /**
     * Gets a metric card by ID.
     * 
     * @param {string} metricId
     * @returns {Object|null} MetricCard instance
     */
    function getCard(metricId) {
        return metricCards[metricId] || null;
    }
    
    /**
     * Gets all metric cards.
     * 
     * @returns {Object} Map of metricId -> MetricCard
     */
    function getAllCards() {
        return metricCards;
    }
    
    /**
     * Shows a status message (e.g., loading, error).
     * 
     * @param {string} message
     * @param {boolean} isError
     */
    function showMessage(message, isError = false) {
        clear();
        
        const msgDiv = document.createElement('div');
        msgDiv.className = 'status-message' + (isError ? ' error' : '');
        msgDiv.textContent = message;
        dashboardGrid.appendChild(msgDiv);
    }
    
    /**
     * Shows empty state with icon.
     * 
     * @param {string} message
     */
    function showEmptyState(message) {
        clear();
        
        const emptyDiv = document.createElement('div');
        emptyDiv.className = 'empty-state';
        emptyDiv.innerHTML = `
            <div class="empty-state-icon">📊</div>
            <h3 class="empty-state-title">No Data</h3>
            <p class="empty-state-description">${message}</p>
        `;
        dashboardGrid.appendChild(emptyDiv);
    }
    
    // Public API
    return {
        init,
        clear,
        createCards,
        getCard,
        getAllCards,
        showMessage,
        showEmptyState
    };
    
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = DashboardView;
}

