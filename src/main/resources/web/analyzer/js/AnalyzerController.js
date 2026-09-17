
import * as AnalyticsApi from './api/AnalyticsApi.js';
import * as HeaderView from './ui/HeaderView.js';
import * as DashboardView from './ui/DashboardView.js';
import * as DuckDBClient from './data/DuckDBClient.js';
import * as MetricCardView from './ui/MetricCardView.js';
import { dismissClosableNotice } from '../../shared/notice/Notice.js';
import {
    RunUnavailableError, RunWaiter, WAIT_INTERVAL_MS, chooseInitialRunId, fetchPipelineStatus,
    isRunStarting, showLoadFailedNotice, showRunUnavailableNotice, waitWithStartNotice
} from '../../shared/run/RunAvailability.js';

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
        'death_lifetimes',      // 5. Death Lifetimes
        'genome_clades',        // 6. Clade Shares
        'genome_diversity',     // 7. Genome Diversity
        'instruction_usage',    // 8. Instruction Usage
        'environment_composition' // 9. Environment Composition
    ];
    
    // State
    let currentRunId = null;
    let manifest = null;
    let isLoading = false;
    /** Increases with every dashboard load, so a load that was overtaken leaves the page alone. */
    let loadGeneration = 0;
    /** Pipeline status as last fetched by this controller; the footer's poll takes over once it has one. */
    let pipeline = null;
    /** Waits for the manifest of a starting run. */
    const manifestWaiter = new RunWaiter();
    /** Pending reloads of cards that wait for their first data, keyed by metric ID. */
    const dataRetryTimers = {};
    /** @type {Object<string, AbortController>} Active abort controllers per metric ID */
    const abortControllers = {};

    /**
     * Per-metric view state a chart asked for, keyed by metric ID.
     *
     * A chart that lets the reader choose what it shows - which clade is opened, say - stores that
     * choice here and gets it back on every render, so it survives a redraw and a change of level
     * of detail. Charts that show one fixed thing never touch it.
     */
    const viewStates = {};

    /** Rows last loaded per metric ID, so a view change redraws without fetching again. */
    const loadedData = {};

    /** Hard cap on data points loaded per chart */
    const HARD_CAP = 5000;

    /**
     * Per-metric window state for tick-range scrolling.
     * @type {Object<string, {tickMin: number, tickMax: number, viewFrom: number, viewTo: number, effectiveLimit: number, isParquet: boolean}>}
     */
    const windowState = {};

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
     * Loads the dashboard of the run in the URL, or, without one, of the starting run or else the
     * newest run with data.
     */
    async function loadRuns() {
        try {
            HeaderView.setLoading(true);
            
            const [runs, status] = await Promise.all([AnalyticsApi.listRuns(), fetchPipelineStatus()]);
            pipeline = status;

            // Without a run in the URL: the starting run, otherwise the newest one with data
            if (!currentRunId) {
                currentRunId = chooseInitialRunId(runs, pipeline);
                if (!currentRunId) {
                    await showRunUnavailableNotice(new RunUnavailableError(null));
                    return;
                }
                updateUrlRunId(currentRunId);
            }
            window.footer?.updateCurrent?.();
            await loadDashboard(currentRunId);

        } catch (error) {
            console.error('[AnalyzerController] Failed to load runs:', error);
            showLoadFailedNotice('Could not load the run list', error);
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

        // Preserve other params and ensure runId is last
        const tick = url.searchParams.get('tick');
        const organism = url.searchParams.get('organism');
        url.searchParams.delete('tick');
        url.searchParams.delete('organism');
        url.searchParams.delete('runId');

        if (tick) url.searchParams.set('tick', tick);
        if (organism) url.searchParams.set('organism', organism);
        if (runId) url.searchParams.set('runId', runId);

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
        // A load still waiting for a starting run gives way; any other load in progress wins
        if (isLoading && !manifestWaiter.isWaiting()) return;
        manifestWaiter.cancel();
        clearDataRetries();
        const generation = ++loadGeneration;
        isLoading = true;
        
        try {
            dismissClosableNotice();
            HeaderView.setLoading(true);
            DashboardView.showMessage('Loading metrics...');
            
            // Fetch manifest; a starting run has none yet and is waited for
            manifest = await AnalyticsApi.getManifest(runId);
            if (!manifest.metrics || manifest.metrics.length === 0) {
                manifest = await waitForManifest(runId);
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

            // Register LOD change and scroll handlers
            const cards = DashboardView.getAllCards();
            for (const [metricId, card] of Object.entries(cards)) {
                MetricCardView.setOnLodChange(card, (lod) => {
                    card.selectedLod = lod;
                    card._lodSwitching = true;
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

                MetricCardView.setOnScroll(card, (scrollRatio) => {
                    handleScroll(card, scrollRatio).catch(error => {
                        if (error.name !== 'AbortError') {
                            console.error(`[AnalyzerController] Scroll error for ${metricId}:`, error);
                        }
                    });
                });
            }

            // Load data for all metrics
            await loadAllMetricsData();
            
        } catch (error) {
            if (generation !== loadGeneration || error.name === 'AbortError') {
                return;
            }
            DashboardView.showMessage('');
            if (error instanceof RunUnavailableError) {
                await showRunUnavailableNotice(error);
                return;
            }
            console.error('[AnalyzerController] Failed to load dashboard:', error);
            showLoadFailedNotice('Could not load the analyzer dashboard', error);
        } finally {
            if (generation === loadGeneration) {
                isLoading = false;
                HeaderView.setLoading(false);
            }
        }
    }

    /**
     * Waits until the manifest of a starting run lists metrics, showing the start card meanwhile.
     *
     * @param {string} runId - Simulation run ID
     * @returns {Promise<Object>} The manifest
     * @throws {RunUnavailableError} If the run is not starting, or stops before its metrics appear
     */
    async function waitForManifest(runId) {
        pipeline = await fetchPipelineStatus();
        if (!isRunStarting(pipeline, runId)) {
            throw new RunUnavailableError(runId);
        }
        DashboardView.showMessage('');
        return waitWithStartNotice(manifestWaiter, runId, async () => {
            const candidate = await AnalyticsApi.getManifest(runId);
            return candidate.metrics && candidate.metrics.length > 0 ? candidate : null;
        });
    }

    /**
     * Shows that a card has no data. While the run shown is starting, more may come: the card
     * says so and loads itself again after a while, until it has data or the run stops.
     *
     * @param {Object} card - MetricCard instance
     */
    function showNoDataOrRetry(card) {
        const runId = currentRunId;
        const polled = window.footer?.pipelineState?.();
        const status = polled && polled.status ? polled : pipeline;
        if (!isRunStarting(status, runId)) {
            MetricCardView.showNoData(card);
            return;
        }
        MetricCardView.showWaitingForData(card);
        const metricId = card.metric.id;
        clearTimeout(dataRetryTimers[metricId]);
        dataRetryTimers[metricId] = setTimeout(() => {
            delete dataRetryTimers[metricId];
            if (runId !== currentRunId || DashboardView.getAllCards()[metricId] !== card) return;
            loadMetricData(card).catch(error => {
                if (error.name === 'AbortError') return;
                console.error(`[AnalyzerController] Failed to reload metric ${metricId}:`, error);
                MetricCardView.showError(card, error.message || 'Failed to load data');
            });
        }, WAIT_INTERVAL_MS);
    }

    /** Drops every pending card reload, as when another run or dashboard is loaded. */
    function clearDataRetries() {
        for (const metricId of Object.keys(dataRetryTimers)) {
            clearTimeout(dataRetryTimers[metricId]);
            delete dataRetryTimers[metricId];
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
     * Calculates the effective data point limit for a card.
     * Uses the plugin's maxDataPoints if set, otherwise falls back to pixel-based heuristic.
     *
     * @param {Object} card - Card instance
     * @returns {number}
     */
    function calculateEffectiveLimit(card) {
        if (card.metric.maxDataPoints) {
            return Math.min(HARD_CAP, card.metric.maxDataPoints);
        }
        const canvas = card.element.querySelector('canvas');
        const chartWidth = canvas ? canvas.clientWidth : 800;
        return Math.min(HARD_CAP, Math.max(100, Math.floor(chartWidth / 2)));
    }

    /**
     * How many points a level of detail holds over a tick range.
     *
     * The manifest names the tick distance between two rows of each level, so this is a count and
     * not an estimate. Metrics writing several rows per tick - one per genome, say - still hold as
     * many points as the chart draws: what is counted is moments in time.
     *
     * @param {Object} metric - Manifest entry
     * @param {string} lod - Level of detail, e.g. "lod2"
     * @param {number} tickMin - First tick of the metric
     * @param {number} tickMax - Last tick of the metric
     * @returns {number} The number of points
     * @throws {Error} If the manifest names no interval for that level
     */
    function pointCount(metric, lod, tickMin, tickMax) {
        const interval = metric.tickIntervals?.[lod];
        if (!interval) {
            throw new Error(
                `Metric ${metric.id} names no tick interval for ${lod}; its manifest was written ` +
                `by a build that did not record one, and this build cannot read its data either.`);
        }
        return Math.floor((tickMax - tickMin) / interval) + 1;
    }

    /**
     * The coarsest level of detail a metric offers - its overview.
     *
     * @param {Object} metric - Manifest entry
     * @returns {string|null} The coarsest level, or null if the metric names none
     */
    function coarsestLod(metric) {
        const levels = metric.dataSources ? Object.keys(metric.dataSources).sort() : [];
        return levels.length > 0 ? levels[levels.length - 1] : null;
    }

    /**
     * Keeps every nth row so that at most `limit` ticks remain, and returns the rest unchanged.
     *
     * The overview must show the whole run; when it holds more moments than the chart can draw,
     * they are thinned evenly rather than cut off at one end. Rows sharing a tick stay together,
     * so a metric with several rows per moment keeps its moments whole.
     *
     * @param {Array<Object>} rows - Rows ordered by tick
     * @param {number} limit - Greatest number of ticks to keep
     * @returns {Array<Object>} The thinned rows
     */
    function thinToLimit(rows, limit) {
        const ticks = [...new Set(rows.map(row => Number(row.tick)))];
        if (ticks.length <= limit) {
            return rows;
        }

        const step = Math.ceil(ticks.length / limit);
        const kept = new Set(ticks.filter((_, index) => index % step === 0));
        return rows.filter(row => kept.has(Number(row.tick)));
    }

    /**
     * Loads data for a single metric card with windowed tick-range support.
     * <p>
     * Two-phase approach for fast loading:
     * 1. Lightweight /tick-range pre-check (~1ms) determines if windowing is needed.
     * 2. Data request uses tickFrom/tickTo so the server only merges the needed files.
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
            const hasGeneratedQuery = metric.generatedQuery && metric.generatedQuery.trim();
            const selectedLod = card.selectedLod || null;
            const isParquet = !!hasGeneratedQuery;

            // Preserve tick position on LOD switch
            const prevState = windowState[metricId];
            const keepPosition = card._lodSwitching && prevState;
            delete card._lodSwitching;

            const effectiveLimit = calculateEffectiveLimit(card);

            // Phase 1: Lightweight tick-range pre-check
            const rangeInfo = await AnalyticsApi.fetchTickRange(metricId, currentRunId, selectedLod);
            const tickMin = rangeInfo.tickMin;
            const tickMax = rangeInfo.tickMax;
            const resolvedLod = rangeInfo.lod || selectedLod;
            // The overview is never windowed: it answers what the whole run looks like, and a
            // window would answer something else. Too many points there are thinned after loading.
            const isOverview = resolvedLod === coarsestLod(metric);
            const hasRange = tickMin != null && tickMax != null;
            const points = hasRange ? pointCount(metric, resolvedLod, tickMin, tickMax) : 0;
            const needsWindowing = !isOverview && hasRange && points > effectiveLimit;

            // Calculate view window if windowing is needed
            let viewFrom = null;
            let viewTo = null;
            if (needsWindowing) {
                const totalRange = tickMax - tickMin;
                const viewRange = Math.max(1, Math.round(totalRange * (effectiveLimit / points)));

                if (keepPosition && prevState && prevState.viewFrom != null) {
                    viewFrom = Math.max(tickMin, Math.min(prevState.viewFrom, tickMax - viewRange));
                    viewTo = viewFrom + viewRange;
                } else {
                    // Default: latest data (rightmost)
                    viewTo = tickMax;
                    viewFrom = tickMax - viewRange;
                }
            }

            // Phase 2: Fetch data with or without tick range limits
            let data;

            if (isParquet) {
                // Fetch Parquet blob — server only merges files in the tick window
                const { blob: parquetBlob } = await AnalyticsApi.fetchParquetBlob(
                    metricId, currentRunId, resolvedLod, controller.signal, viewFrom, viewTo
                );

                const blobKey = `${metricId}_${resolvedLod || 'auto'}`;
                await DuckDBClient.registerParquetBlob(blobKey, parquetBlob);

                data = await DuckDBClient.queryRegisteredBlob(blobKey, metric.generatedQuery);

                if (needsWindowing) {
                    windowState[metricId] = {
                        tickMin, tickMax, viewFrom, viewTo, effectiveLimit,
                        isParquet: true, blobKey, points
                    };
                    MetricCardView.showScrollbar(card, windowState[metricId]);
                } else {
                    delete windowState[metricId];
                    MetricCardView.hideScrollbar(card);
                }

            } else {
                // JSON path — server-side query with tick range
                const result = await AnalyticsApi.queryData(
                    currentRunId, metricId, resolvedLod, controller.signal, viewFrom, viewTo
                );
                data = result.data;

                if (needsWindowing) {
                    windowState[metricId] = {
                        tickMin, tickMax, viewFrom, viewTo, effectiveLimit,
                        isParquet: false, points
                    };
                    MetricCardView.showScrollbar(card, windowState[metricId]);
                } else {
                    delete windowState[metricId];
                    MetricCardView.hideScrollbar(card);
                }
            }

            // Highlight the active LOD chip
            if (resolvedLod) {
                MetricCardView.setActiveLod(card, resolvedLod);
            }

            if (data.length === 0) {
                showNoDataOrRetry(card);
                return;
            }

            const companion = await loadCompanionData(metric, resolvedLod, controller.signal);
            loadedData[metricId] = {
                data: isOverview ? thinToLimit(data, effectiveLimit) : data,
                companion
            };
            renderWithViewState(card);

        } catch (error) {
            if (error.name === 'AbortError') {
                throw error;
            }
            if (error.code === 'NO_DATA') {
                showNoDataOrRetry(card);
                return;
            }
            console.error(`[Analytics] Error loading metric ${metricId}:`, error);
            throw error;
        } finally {
            if (abortControllers[metricId] === controller) {
                delete abortControllers[metricId];
            }
        }
    }

    /**
     * Loads the companion tables of a metric, or returns null when it has none.
     *
     * The rows arrive keyed by the metric id of the table they came from, which is how a chart
     * asks for the one it means: the ids stand in its visualization config, so a chart reading two
     * companions never depends on the order the manifest happens to list them in.
     *
     * A companion is read at its finest level of detail: it carries structure, and a thinned-out
     * structure is not a coarser view of it but a wrong one - a lineage missing edges turns
     * descendants into roots. A companion that carries values over time instead says so
     * ({@code followsLevel}) and is read at the level the chart shows, as its own data is. They are read again whenever the metric is loaded, because a running
     * simulation keeps adding to them, and a tree that stops growing loses every genome born after
     * it was read.
     *
     * Scrolling is not such a moment. It moves the window inside the tick range that was known
     * when the metric was loaded, and the copy read then already covers that whole range, so the
     * scroll path carries it along instead of asking for it again. Opening a clade likewise
     * redraws from what is already loaded and costs no request.
     *
     * @param {Object} metric - Manifest entry of the metric being loaded
     * @param {string|null} lod - Level of detail the chart shows, for companions following it
     * @param {AbortSignal} signal - Signal aborting the fetch
     * @returns {Promise<Object<string, Array<Object>>|null>} Rows per companion metric id, or null
     *          if the metric has none
     */
    async function loadCompanionData(metric, lod, signal) {
        if (!metric.companions || metric.companions.length === 0) {
            return null;
        }

        const rowsByMetric = {};
        for (const companion of metric.companions) {
            const blobKey = `companion_${metric.id}_${companion.metricId}`;
            const level = companion.followsLevel && lod ? lod : 'lod0';
            const { blob } = await AnalyticsApi.fetchParquetBlob(
                companion.metricId, currentRunId, level, signal
            );
            await DuckDBClient.registerParquetBlob(blobKey, blob);
            rowsByMetric[companion.metricId] =
                await DuckDBClient.queryRegisteredBlob(blobKey, companion.query);
        }
        return rowsByMetric;
    }

    /**
     * Draws a card from the rows already loaded, in the view state it currently holds.
     *
     * Charts ask for a new view state through the callback; the redraw uses the same rows, so
     * changing what a chart shows never costs a request.
     *
     * @param {Object} card - Card instance
     */
    function renderWithViewState(card) {
        const metricId = card.metric.id;
        const loaded = loadedData[metricId];
        if (!loaded) return;

        MetricCardView.renderChart(card, loaded.data, {
            companion: loaded.companion,
            viewState: viewStates[metricId] || null,
            onViewStateChange: (next) => {
                viewStates[metricId] = next;
                renderWithViewState(card);
            }
        });
    }

    /**
     * Handles scroll events for a metric card.
     * Recomputes the view window from the scroll ratio and reloads data.
     *
     * @param {Object} card - Card instance
     * @param {number} scrollRatio - 0.0 (start) to 1.0 (end)
     */
    async function handleScroll(card, scrollRatio) {
        const metricId = card.metric.id;
        const state = windowState[metricId];
        if (!state) return;

        const totalRange = state.tickMax - state.tickMin;
        const viewRange = state.viewTo - state.viewFrom;
        const maxOffset = totalRange - viewRange;

        const viewFrom = state.tickMin + Math.round(scrollRatio * maxOffset);
        const viewTo = viewFrom + viewRange;

        // Update state
        state.viewFrom = viewFrom;
        state.viewTo = viewTo;

        // Abort any in-flight request
        if (abortControllers[metricId]) {
            abortControllers[metricId].abort();
        }
        const controller = new AbortController();
        abortControllers[metricId] = controller;

        try {
            MetricCardView.showLoading(card);
            let data;
            const selectedLod = card.selectedLod || null;

            if (state.isParquet) {
                // Re-fetch windowed blob from server and re-register
                const { blob: parquetBlob } = await AnalyticsApi.fetchParquetBlob(
                    metricId, currentRunId, selectedLod, controller.signal, viewFrom, viewTo
                );
                await DuckDBClient.registerParquetBlob(state.blobKey, parquetBlob);
                data = await DuckDBClient.queryRegisteredBlob(state.blobKey, card.metric.generatedQuery);
            } else {
                // Server-side query with tick range
                const result = await AnalyticsApi.queryData(
                    currentRunId, metricId, selectedLod, controller.signal, viewFrom, viewTo
                );
                data = result.data;
            }

            if (data.length === 0) {
                MetricCardView.showNoData(card);
                return;
            }

            // The companion is not windowed, and the scrollbar cannot leave the tick range that
            // was known when the metric was loaded - so the copy from then covers every tick
            // reachable by scrolling. It is fetched here only when the metric was loaded without
            // rows and the scroll is what first reaches some.
            const companion = loadedData[metricId]?.companion
                ?? await loadCompanionData(card.metric, selectedLod, controller.signal);
            loadedData[metricId] = { data, companion };
            renderWithViewState(card);

        } catch (error) {
            if (error.name === 'AbortError') return;
            console.error(`[Analytics] Scroll error for ${metricId}:`, error);
        } finally {
            if (abortControllers[metricId] === controller) {
                delete abortControllers[metricId];
            }
        }
    }
    
export const changeRun = handleRunChange;
export const getCurrentRunId = () => currentRunId;

