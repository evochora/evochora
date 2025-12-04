/**
 * Metric Card View Component
 * 
 * Renders a single metric with its chart.
 * LOD selection is handled automatically by the server.
 * 
 * @module MetricCardView
 */

const MetricCardView = (function() {
    'use strict';
    
    /**
     * Creates a new metric card.
     * 
     * @param {Object} metric - ManifestEntry from manifest
     * @returns {Object} MetricCard instance
     */
    function create(metric) {
        const card = {
            metric: metric,
            element: null,
            canvas: null,
            chart: null,
            loadingOverlay: null,
            errorOverlay: null
        };
        
        // Build DOM
        card.element = document.createElement('div');
        card.element.className = 'metric-card';
        card.element.dataset.metricId = metric.id;
        
        // Header
        const header = document.createElement('div');
        header.className = 'metric-card-header';
        
        const titleContainer = document.createElement('div');
        
        const title = document.createElement('h3');
        title.className = 'metric-card-title';
        title.textContent = metric.name || metric.id;
        titleContainer.appendChild(title);
        
        if (metric.description) {
            const desc = document.createElement('p');
            desc.className = 'metric-card-description';
            desc.textContent = metric.description;
            titleContainer.appendChild(desc);
        }
        
        header.appendChild(titleContainer);
        card.element.appendChild(header);
        
        // Chart container
        const chartContainer = document.createElement('div');
        chartContainer.className = 'chart-container';
        
        card.canvas = document.createElement('canvas');
        chartContainer.appendChild(card.canvas);
        
        // Loading overlay
        card.loadingOverlay = document.createElement('div');
        card.loadingOverlay.className = 'chart-loading';
        card.loadingOverlay.innerHTML = '<span class="chart-loading-text">Loading data...</span>';
        card.loadingOverlay.style.display = 'none';
        chartContainer.appendChild(card.loadingOverlay);
        
        // Error overlay
        card.errorOverlay = document.createElement('div');
        card.errorOverlay.className = 'chart-error';
        card.errorOverlay.innerHTML = '<span class="chart-error-text"></span>';
        card.errorOverlay.style.display = 'none';
        chartContainer.appendChild(card.errorOverlay);
        
        card.element.appendChild(chartContainer);
        
        return card;
    }
    
    /**
     * Renders the chart with data.
     * 
     * @param {Object} card - MetricCard instance
     * @param {Array<Object>} data - Data rows
     */
    function renderChart(card, data) {
        // Destroy existing chart
        if (card.chart) {
            if (card.chart.destroy) {
                card.chart.destroy();
            }
            card.chart = null;
        }
        
        // Hide overlays
        card.loadingOverlay.style.display = 'none';
        card.errorOverlay.style.display = 'none';
        
        if (!data || data.length === 0) {
            showError(card, 'No data available');
            return;
        }
        
        // Get visualization config
        const viz = card.metric.visualization || {};
        const chartType = viz.type || 'line-chart';
        const config = viz.config || {};
        
        try {
            card.chart = ChartRegistry.render(chartType, card.canvas, data, config);
        } catch (error) {
            console.error('[MetricCardView] Chart render failed:', error);
            showError(card, `Chart error: ${error.message}`);
        }
    }
    
    /**
     * Shows loading state.
     * 
     * @param {Object} card - MetricCard instance
     */
    function showLoading(card) {
        card.loadingOverlay.style.display = 'flex';
        card.errorOverlay.style.display = 'none';
    }
    
    /**
     * Shows error state.
     * 
     * @param {Object} card - MetricCard instance
     * @param {string} message - Error message
     */
    function showError(card, message) {
        card.loadingOverlay.style.display = 'none';
        card.errorOverlay.style.display = 'flex';
        card.errorOverlay.classList.remove('no-data');
        card.errorOverlay.querySelector('.chart-error-text').textContent = message;
    }
    
    /**
     * Shows "no data yet" state (not an error, just waiting for data).
     * 
     * @param {Object} card - MetricCard instance
     */
    function showNoData(card) {
        card.loadingOverlay.style.display = 'none';
        card.errorOverlay.style.display = 'flex';
        card.errorOverlay.classList.add('no-data');
        card.errorOverlay.querySelector('.chart-error-text').textContent = 'No data yet';
    }
    
    /**
     * Destroys the metric card and cleans up.
     * 
     * @param {Object} card - MetricCard instance
     */
    function destroy(card) {
        if (card.chart && card.chart.destroy) {
            card.chart.destroy();
        }
        if (card.element && card.element.parentNode) {
            card.element.parentNode.removeChild(card.element);
        }
    }
    
    // Public API
    return {
        create,
        renderChart,
        showLoading,
        showError,
        showNoData,
        destroy
    };
    
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = MetricCardView;
}

