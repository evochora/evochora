/**
 * Analyzer Controller
 * 
 * Main application controller. Coordinates between API and UI components.
 * Handles data loading and chart updates.
 * 
 * Uses client-side DuckDB WASM for flexible local queries on merged Parquet files.
 * The server only merges Parquet files; all SQL transformations happen in the browser.
 * 
 * @module AnalyzerController
 */

const AnalyzerController = (function() {
    'use strict';
    
    // Preferred display order for metrics (metrics not in list appear at the end)
    const METRIC_ORDER = [
        'population',           // 1. Population Overview
        'vital_stats',          // 2. Birth & Death Rates
        'generation_depth',     // 3. Generation Depth
        'age_distribution',     // 4. Age Distribution
        'instruction_usage',    // 5. Instruction Usage
        'environment_composition' // 6. Environment Composition
    ];
    
    // State
    let currentRunId = null;
    let manifest = null;
    let isLoading = false;
    
    /**
     * Initializes the controller and UI components.
     */
    async function init() {
        console.debug('[AnalyzerController] Initializing...');
        
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
        
        console.debug('[AnalyzerController] Initialized');
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
                HeaderView.setRuns(runs, currentRunId); // Ensure dropdown is updated
                await loadDashboard(currentRunId);
            } else if (runs.length > 0) {
                HeaderView.setRuns(runs, currentRunId);
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
        if (!runId || runId === currentRunId) return;
        
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
            
            // Sort metrics by preferred order
            manifest.metrics.sort((a, b) => {
                const orderA = METRIC_ORDER.indexOf(a.id);
                const orderB = METRIC_ORDER.indexOf(b.id);
                // Metrics not in METRIC_ORDER go to the end (alphabetically)
                if (orderA === -1 && orderB === -1) return a.id.localeCompare(b.id);
                if (orderA === -1) return 1;
                if (orderB === -1) return -1;
                return orderA - orderB;
            });
            
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
                // Extract user-friendly error message (hide technical details)
                let message = error.message || 'Failed to load data';
                if (message.includes('Binder Error') || message.includes('Parser Error')) {
                    message = 'Query error - data may be incomplete';
                }
                console.error(`[AnalyzerController] Failed to load metric ${metricId}:`, error);
                MetricCardView.showError(card, message);
            });
        });
        
        await Promise.all(promises);
    }
    
    /**
     * Loads data for a single metric card.
     * Uses client-side DuckDB WASM for queries on merged Parquet from server.
     * 
     * @param {Object} card - MetricCard instance
     */
    async function loadMetricData(card) {
        MetricCardView.showLoading(card);
        
        const metric = card.metric;
        
        try {
            const startTime = performance.now();
            
            // Check if metric has a generated query (new stateless plugins)
            const hasGeneratedQuery = metric.generatedQuery && metric.generatedQuery.trim();
            
            let data;
            if (hasGeneratedQuery) {
                // New architecture: Client-side DuckDB WASM
                // 1. Fetch merged Parquet blob from server
                const parquetBlob = await AnalyticsApi.fetchParquetBlob(metric.id, currentRunId);
                
                // 2. Query locally with DuckDB WASM using the generated SQL
                data = await DuckDBClient.queryParquetBlob(parquetBlob, metric.generatedQuery);
                
                const duration = Math.round(performance.now() - startTime);
                console.debug(`[AnalyzerController] ${metric.id}: ${data.length} rows loaded via DuckDB WASM in ${duration}ms`);
            } else {
                // Legacy: Server-side query (for plugins without generatedQuery)
                data = await AnalyticsApi.queryData(currentRunId, metric.id);
                
                const duration = Math.round(performance.now() - startTime);
                console.debug(`[AnalyzerController] ${metric.id}: ${data.length} rows loaded via server in ${duration}ms`);
            }
            
            if (data.length === 0) {
                MetricCardView.showNoData(card);
                return;
            }
            
            // Render chart
            MetricCardView.renderChart(card, data);
            
        } catch (error) {
            // Handle "no data yet" as a non-error state
            if (error.code === 'NO_DATA') {
                MetricCardView.showNoData(card);
                return;
            }
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

