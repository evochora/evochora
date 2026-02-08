
import * as AnalyticsApi from './api/AnalyticsApi.js';
import * as HeaderView from './ui/HeaderView.js';
import * as DashboardView from './ui/DashboardView.js';
import * as DuckDBClient from './data/DuckDBClient.js';
import * as MetricCardView from './ui/MetricCardView.js';

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

    const METRIC_ORDER = [
        'population',           // 1. Population Overview
        'vital_stats',          // 2. Birth & Death Rates
        'generation_depth',     // 3. Generation Depth
        'age_distribution',     // 4. Age Distribution
        'genome_population',    // 5. Genome Population
        'genome_diversity',     // 6. Genome Diversity
        'instruction_usage',    // 7. Instruction Usage
        'environment_composition' // 8. Environment Composition
    ];
    
    // State
    let currentRunId = null;
    let manifest = null;
    let isLoading = false;
    /** @type {Object<string, AbortController>} Active abort controllers per metric ID */
    const abortControllers = {};

    /**
     * Initializes the controller and UI components.
     */
export async function init() {
        // Check URL for runId parameter (e.g., from AppSwitcher navigation)
        const urlParams = new URLSearchParams(window.location.search);
        const urlRunId = urlParams.get('runId');
        if (urlRunId) {
            currentRunId = urlRunId;
        }

        // Initialize UI components
        HeaderView.init({
            onRefresh: handleRefresh
        });
        
        DashboardView.init();
        
        // Update logo width for loading animation
        HeaderView.updateLogoWidth();
        window.addEventListener('resize', HeaderView.updateLogoWidth);
        
        // Load available runs
        await loadRuns();
    }
    
    /**
 * Loads available runs and auto-selects the latest if none selected.
     */
    async function loadRuns() {
        try {
            HeaderView.setLoading(true);
            
            const runs = await AnalyticsApi.listRuns();
            
        // Auto-load first (latest) run if available and none selected
        if (runs.length > 0 && !currentRunId) {
            // Sort by startTime or runId timestamp (newest first)
            const sorted = [...runs].sort((a, b) => {
                const scoreA = a.startTime || extractTimestamp(a.runId);
                const scoreB = b.startTime || extractTimestamp(b.runId);
                return scoreB - scoreA;
            });
            currentRunId = sorted[0].runId;
            updateUrlRunId(currentRunId);
            window.footer?.updateCurrent?.();
            await loadDashboard(currentRunId);
        } else if (currentRunId) {
            window.footer?.updateCurrent?.();
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
 * Extracts a sortable timestamp from a runId.
 */
function extractTimestamp(runId) {
    const m = (runId || '').match(/^(\d{8})-(\d{8})/);
    return m ? Number(m[1] + m[2]) : 0;
}
    
    /**
     * Handles run selection change.
     */
    async function handleRunChange(runId) {
        if (!runId || runId === currentRunId) return;

        currentRunId = runId;
        updateUrlRunId(runId);
        window.footer?.updateCurrent?.();
        await loadDashboard(runId);
    }

    /**
     * Updates the URL with the current runId (for AppSwitcher navigation).
     * Uses replaceState to avoid polluting browser history.
     */
    function updateUrlRunId(runId) {
        const url = new URL(window.location.href);
        if (runId) {
            url.searchParams.set('runId', runId);
        } else {
            url.searchParams.delete('runId');
        }
        window.history.replaceState({}, '', url.toString());
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
export async function loadDashboard(runId) {
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

            // Register LOD change handlers
            const cards = DashboardView.getAllCards();
            for (const [metricId, card] of Object.entries(cards)) {
                MetricCardView.setOnLodChange(card, (lod) => {
                    card.selectedLod = lod;
                    MetricCardView.setActiveLod(card, lod);
                    loadMetricData(card).catch(error => {
                        if (error.name !== 'AbortError') {
                            let message = error.message || 'Failed to load data';
                            if (message.includes('Binder Error') || message.includes('Parser Error')) {
                                message = 'Query error - data may be incomplete';
                            }
                            console.error(`[AnalyzerController] Failed to load metric ${metricId}:`, error);
                            MetricCardView.showError(card, message);
                        }
                    });
                });
            }

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
                if (error.name === 'AbortError') return; // LOD switch interrupted this load
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
        const metric = card.metric;
        const metricId = metric.id;

        // Abort any in-flight request for this metric
        if (abortControllers[metricId]) {
            abortControllers[metricId].abort();
        }
        const controller = new AbortController();
        abortControllers[metricId] = controller;

        MetricCardView.showLoading(card);

        try {
            // Check if metric has a generated query (new stateless plugins)
            const hasGeneratedQuery = metric.generatedQuery && metric.generatedQuery.trim();
            const selectedLod = card.selectedLod || null;

            let data;
            let resolvedLod = selectedLod;

            if (hasGeneratedQuery) {
                // Client-side DuckDB WASM query on merged Parquet from server
                const { blob: parquetBlob, metadata } = await AnalyticsApi.fetchParquetBlob(
                    metricId, currentRunId, selectedLod, controller.signal
                );

                if (metadata.lod && metadata.lod !== 'unknown') {
                    resolvedLod = metadata.lod;
                }

                data = await DuckDBClient.queryParquetBlob(parquetBlob, metric.generatedQuery);
            } else {
                // Server-side query (for plugins without generatedQuery)
                const result = await AnalyticsApi.queryData(currentRunId, metricId, selectedLod, controller.signal);
                data = result.data;

                if (result.lod) {
                    resolvedLod = result.lod;
                }
            }

            // Highlight the active LOD chip
            if (resolvedLod) {
                MetricCardView.setActiveLod(card, resolvedLod);
            }

            if (data.length === 0) {
                MetricCardView.showNoData(card);
                return;
            }

            // Render chart
            MetricCardView.renderChart(card, data);

        } catch (error) {
            if (error.name === 'AbortError') {
                throw error; // Re-throw so callers can handle
            }
            // Handle "no data yet" as a non-error state
            if (error.code === 'NO_DATA') {
                MetricCardView.showNoData(card);
                return;
            }
            console.error(`[Analytics] Error loading metric ${metricId}:`, error);
            throw error;
        } finally {
            // Clean up controller if it's still the current one
            if (abortControllers[metricId] === controller) {
                delete abortControllers[metricId];
            }
        }
    }
    
    /**
     * Shows a global error message.
     * 
     * @param {string} message
     */
export function showError(message) {
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
export function hideError() {
        const errorBar = document.getElementById('error-banner');
        if (errorBar) {
            errorBar.classList.remove('visible');
        }
    }
    
export const changeRun = handleRunChange;
export const getCurrentRunId = () => currentRunId;

