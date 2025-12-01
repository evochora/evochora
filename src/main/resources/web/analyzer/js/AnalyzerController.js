/**
 * Analyzer Controller
 * 
 * Main application controller. Coordinates between API and UI components.
 * Handles data loading and chart updates.
 * 
 * Uses server-side data aggregation with automatic LOD selection.
 * 
 * @module AnalyzerController
 */

const AnalyzerController = (function() {
    'use strict';
    
    // State
    let currentRunId = null;
    let manifest = null;
    let isLoading = false;
    
    /**
     * Initializes the controller and UI components.
     */
    async function init() {
        console.log('[AnalyzerController] Initializing...');
        
        // Initialize UI components
        HeaderView.init({
            onRunChange: handleRunChange,
            onRefresh: handleRefresh
        });
        
        DashboardView.init();
        
        // Update logo width for loading animation
        HeaderView.updateLogoWidth();
        window.addEventListener('resize', HeaderView.updateLogoWidth);
        
        // Load available runs
        await loadRuns();
        
        console.log('[AnalyzerController] Initialized');
    }
    
    /**
     * Loads available runs from the API and populates the dropdown.
     */
    async function loadRuns() {
        try {
            HeaderView.setLoading(true);
            
            const runs = await AnalyticsApi.listRuns();
            HeaderView.setRuns(runs, currentRunId);
            
            // Auto-load first run if available
            if (runs.length > 0 && !currentRunId) {
                const firstRun = runs[0];
                currentRunId = firstRun.runId;
                await loadDashboard(currentRunId);
            }
            
        } catch (error) {
            console.error('[AnalyzerController] Failed to load runs:', error);
            showError(`Failed to load runs: ${error.message}`);
        } finally {
            HeaderView.setLoading(false);
        }
    }
    
    /**
     * Handles run selection change.
     */
    async function handleRunChange(runId) {
        if (runId === currentRunId) return;
        
        currentRunId = runId;
        await loadDashboard(runId);
    }
    
    /**
     * Handles refresh button click.
     */
    async function handleRefresh() {
        if (currentRunId) {
            await loadDashboard(currentRunId);
        } else {
            await loadRuns();
        }
    }
    
    /**
     * Loads the dashboard for a specific run.
     * 
     * @param {string} runId - Simulation run ID
     */
    async function loadDashboard(runId) {
        if (isLoading) return;
        isLoading = true;
        
        try {
            HeaderView.setLoading(true);
            DashboardView.showMessage('Loading metrics...');
            
            // Fetch manifest
            manifest = await AnalyticsApi.getManifest(runId);
            
            if (!manifest.metrics || manifest.metrics.length === 0) {
                DashboardView.showEmptyState('No metrics available for this run.');
                return;
            }
            
            // Create metric cards
            DashboardView.createCards(manifest.metrics);
            
            // Load data for all metrics
            await loadAllMetricsData();
            
        } catch (error) {
            console.error('[AnalyzerController] Failed to load dashboard:', error);
            DashboardView.showMessage(`Error: ${error.message}`, true);
        } finally {
            isLoading = false;
            HeaderView.setLoading(false);
        }
    }
    
    /**
     * Loads data for all metric cards.
     */
    async function loadAllMetricsData() {
        const cards = DashboardView.getAllCards();
        
        // Load all metrics in parallel
        const promises = Object.entries(cards).map(([metricId, card]) => {
            return loadMetricData(card).catch(error => {
                console.error(`[AnalyzerController] Failed to load metric ${metricId}:`, error);
                MetricCardView.showError(card, error.message);
            });
        });
        
        await Promise.all(promises);
    }
    
    /**
     * Loads data for a single metric card.
     * Server handles LOD selection automatically.
     * 
     * @param {Object} card - MetricCard instance
     */
    async function loadMetricData(card) {
        MetricCardView.showLoading(card);
        
        const metric = card.metric;
        
        try {
            const startTime = performance.now();
            
            // Query data from server (server handles Auto-LOD)
            const data = await AnalyticsApi.queryData(currentRunId, metric.id);
            
            const duration = Math.round(performance.now() - startTime);
            console.log(`[AnalyzerController] ${metric.id}: ${data.length} rows loaded in ${duration}ms`);
            
            if (data.length === 0) {
                MetricCardView.showError(card, 'No data available');
                return;
            }
            
            // Render chart
            MetricCardView.renderChart(card, data);
            
        } catch (error) {
            console.error(`[AnalyzerController] Error loading metric ${metric.id}:`, error);
            throw error;
        }
    }
    
    /**
     * Shows a global error message.
     * 
     * @param {string} message
     */
    function showError(message) {
        const errorBar = document.getElementById('error-banner');
        const errorMessage = document.getElementById('error-message');
        
        if (errorBar && errorMessage) {
            errorMessage.textContent = message;
            errorBar.classList.add('visible');
        }
    }
    
    /**
     * Hides the global error message.
     */
    function hideError() {
        const errorBar = document.getElementById('error-banner');
        if (errorBar) {
            errorBar.classList.remove('visible');
        }
    }
    
    // Public API
    return {
        init,
        loadRuns,
        loadDashboard,
        showError,
        hideError
    };
    
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = AnalyzerController;
}

