
import * as AnalyticsApi from './api/AnalyticsApi.js';
import * as HeaderView from './ui/HeaderView.js';
import * as DashboardView from './ui/DashboardView.js';
import * as DuckDBClient from './data/DuckDBClient.js';
import * as MetricCardView from './ui/MetricCardView.js';
import * as TickWindowView from './ui/TickWindowView.js';
import { dismissClosableNotice } from '../../shared/notice/Notice.js';
import {
    RunUnavailableError, RunWaiter, WAIT_INTERVAL_MS, chooseInitialRunId, fetchPipelineStatus,
    isRunStarting, showLoadFailedNotice, showRunUnavailableNotice, startingRunId, waitWithStartNotice
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

    /** URL parameters that carry the tick window. */
    const WINDOW_FROM_PARAM = 'from';
    const WINDOW_TO_PARAM = 'to';

    /**
     * The part of the run every card shows, or null for the whole run. It belongs to the page and
     * not to a card, so that a moment found in one chart is looked at in all of them.
     * @type {?{from: number, to: number}}
     */
    let tickWindow = null;

    /**
     * Tick range of the run shown, over all its metrics.
     * @type {?{min: number, max: number}}
     */
    let runExtent = null;

    /**
     * Tick range each metric was last found to hold, keyed by metric ID. Listing a metric's files
     * is what a load costs before any data, so the answer is asked for once per run and again
     * only when a card is reloaded, which is when a running run may have grown.
     */
    const tickRanges = {};

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
        HeaderView.init();
        
        DashboardView.init();

        const from = Number.parseInt(urlParams.get(WINDOW_FROM_PARAM), 10);
        const to = Number.parseInt(urlParams.get(WINDOW_TO_PARAM), 10);
        tickWindow = Number.isFinite(from) && Number.isFinite(to) && to > from ? { from, to } : null;
        if (window.footer?.leftSlot) {
            TickWindowView.init(window.footer.leftSlot, handleTickWindowChange);
        }
        
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

        // Another run holds other ticks: it is shown whole. The first run keeps the URL's window
        if (currentRunId) {
            tickWindow = null;
            writeTickWindowToUrl();
        }
        runExtent = null;
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
     * Reloads one card. The manifest entry is read again, because a running run gains levels of
     * detail; a pinned level stays pinned, and the tick window stays the page's.
     *
     * @param {Object} card - MetricCard instance
     */
    async function refreshCard(card) {
        const runId = currentRunId;
        const metricId = card.metric.id;
        MetricCardView.setRefreshEnabled(card, false);
        try {
            const fresh = await AnalyticsApi.getManifest(runId);
            if (runId !== currentRunId || DashboardView.getAllCards()[metricId] !== card) return;
            const entry = (fresh.metrics || []).find(metric => metric.id === metricId);
            if (entry) MetricCardView.updateMetric(card, entry);
            delete tickRanges[metricId];

            await loadMetricData(card);
        } catch (error) {
            if (error.name === 'AbortError') return;
            console.error(`[AnalyzerController] Failed to reload metric ${metricId}:`, error);
            MetricCardView.showError(card, error.message || 'Failed to load data');
        } finally {
            MetricCardView.setRefreshEnabled(card, true);
        }
    }

    /**
     * Shows the reload button of every card while the run shown is the one the pipeline is
     * producing data for, and hides it otherwise: a run that is not being written cannot change.
     * Called whenever the run shown or the pipeline state changes.
     */
export function updateRefreshVisibility() {
        const pipeline = window.footer?.pipelineState?.();
        const live = !!currentRunId && startingRunId(pipeline) === currentRunId;
        Object.values(DashboardView.getAllCards()).forEach(card => {
            MetricCardView.setRefreshVisible(card, live);
        });
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
            
            // Create the cards, in the order of the manifest
            DashboardView.createCards(manifest.metrics);

            // Register the handlers of the cards
            const cards = DashboardView.getAllCards();
            for (const [metricId, card] of Object.entries(cards)) {
                MetricCardView.setOnLodChange(card, (lod) => {
                    // A click pins a level; a click on the pinned level lets the card choose again
                    card.pinnedLod = card.pinnedLod === lod ? null : lod;
                    if (card.pinnedLod && lod === card.shownLod) {
                        MetricCardView.setActiveLod(card, lod, { pinned: true, tooFine: card.tooFine });
                        return;
                    }
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

                MetricCardView.setOnRefresh(card, () => refreshCard(card));
            }

            updateRefreshVisibility();

            runExtent = await fetchRunExtent(runId, manifest.metrics);
            TickWindowView.show(runExtent, tickWindow);

            // Load the group in view; the others load when they are first opened
            DashboardView.setOnGroupChange(() => {
                loadVisibleMetricsData().catch(error => {
                    console.error('[AnalyzerController] Failed to load group:', error);
                });
            });
            await loadVisibleMetricsData();
            
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
     * Loads data for the cards of the group in view that have not asked for theirs yet. A card is
     * measured when it loads, so it loads while it is visible.
     */
    async function loadVisibleMetricsData() {
        const cards = DashboardView.getActiveCards().filter(card => !card.dataRequested);

        // Load the metrics in parallel
        const promises = cards.map(card => {
            const metricId = card.metric.id;
            card.dataRequested = true;
            return loadMetricData(card, { keepCompanion: true }).catch(error => {
                if (error.name === 'AbortError') return; // another load of the card took over
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
     * The coarsest level of detail a metric offers.
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
     * The coarsest level is drawn even when it holds more moments over the tick window than the
     * chart can draw; they are thinned evenly then rather than cut off at one end. Rows sharing a
     * tick stay together, so a metric with several rows per moment keeps its moments whole.
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
     * Loads data for a single metric card, over the tick window of the page.
     * <p>
     * 1. The ticks the metric holds are known from the load of the run, or asked for.
     * 2. The card draws the finest level of detail whose points over the tick window fit what it
     *    can draw, unless the reader pinned a level; a pinned level that has become too fine for
     *    the window is released.
     * 3. The data request names the window, so the server only merges the files it needs.
     *
     * @param {Object} card - MetricCard instance
     * @param {Object} [options]
     * @param {boolean} [options.keepCompanion=false] - Whether companions read before are kept, as
     *        when only the tick window changed (see {@link loadCompanionData})
     */
    async function loadMetricData(card, { keepCompanion = false } = {}) {
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
            const isParquet = !!hasGeneratedQuery;
            const effectiveLimit = calculateEffectiveLimit(card);

            // Phase 1: which ticks the metric holds
            const rangeInfo = tickRanges[metricId]
                ?? (tickRanges[metricId] =
                    await AnalyticsApi.fetchTickRange(metricId, currentRunId, null));
            const tickMin = rangeInfo.tickMin;
            const tickMax = rangeInfo.tickMax;
            const hasRange = tickMin != null && tickMax != null;
            if (hasRange) extendRunExtent(tickMin, tickMax);

            const from = hasRange ? Math.max(tickMin, tickWindow ? tickWindow.from : tickMin) : null;
            const to = hasRange ? Math.min(tickMax, tickWindow ? tickWindow.to : tickMax) : null;
            if (hasRange && from > to) {
                MetricCardView.showNoData(card);
                return;
            }

            // Phase 2: the level of detail. Levels sort from the finest to the coarsest
            const levels = metric.dataSources ? Object.keys(metric.dataSources).sort() : [];
            const fits = lod => !hasRange || pointCount(metric, lod, from, to) <= effectiveLimit;
            const tooFine = levels.filter(lod => !fits(lod));
            if (card.pinnedLod && tooFine.includes(card.pinnedLod)) {
                card.pinnedLod = null;
            }
            const resolvedLod = card.pinnedLod || levels.find(fits)
                || coarsestLod(metric) || rangeInfo.lod;
            // Only the coarsest level can be drawn although it does not fit; it is thinned then
            const thin = !!resolvedLod && tooFine.includes(resolvedLod);
            const viewFrom = tickWindow ? from : null;
            const viewTo = tickWindow ? to : null;

            // Phase 3: fetch the data of the window
            let data;
            if (isParquet) {
                const { blob: parquetBlob } = await AnalyticsApi.fetchParquetBlob(
                    metricId, currentRunId, resolvedLod, controller.signal, viewFrom, viewTo
                );
                const blobKey = `${metricId}_${resolvedLod || 'auto'}`;
                await DuckDBClient.registerParquetBlob(blobKey, parquetBlob);
                data = await DuckDBClient.queryRegisteredBlob(blobKey, metric.generatedQuery);
            } else {
                const result = await AnalyticsApi.queryData(
                    currentRunId, metricId, resolvedLod, controller.signal, viewFrom, viewTo
                );
                data = result.data;
            }

            card.shownLod = resolvedLod;
            card.tooFine = tooFine;
            if (resolvedLod) {
                MetricCardView.setActiveLod(card, resolvedLod, { pinned: !!card.pinnedLod, tooFine });
            }

            if (data.length === 0) {
                showNoDataOrRetry(card);
                return;
            }

            const previous = loadedData[metricId];
            const followsLevel = (metric.companions || []).some(companion => companion.followsLevel);
            const companionKept = keepCompanion && previous?.companion
                && (!followsLevel || previous.lod === resolvedLod);
            const companion = companionKept
                ? previous.companion
                : await loadCompanionData(metric, resolvedLod, controller.signal);
            loadedData[metricId] = {
                data: thin ? thinToLimit(data, effectiveLimit) : data,
                companion,
                lod: resolvedLod
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
     * A change of the tick window is not such a moment. It moves the window inside the tick range
     * that was known when the metric was loaded, and the copy read then already covers that whole
     * range, so the load carries it along instead of asking for it again - unless the companion
     * follows the level and the level changed with the window. Opening a clade likewise redraws
     * from what is already loaded and costs no request.
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
     * Reads the tick range of a run over all its metrics. Every request is the lightweight one.
     *
     * @param {string} runId - Simulation run ID
     * @param {Array<Object>} metrics - Metric manifest entries
     * @returns {Promise<?{min: number, max: number}>} The range, or null if no metric holds a tick
     */
    async function fetchRunExtent(runId, metrics) {
        Object.keys(tickRanges).forEach(metricId => delete tickRanges[metricId]);
        await Promise.all(metrics.map(async metric => {
            try {
                tickRanges[metric.id] = await AnalyticsApi.fetchTickRange(metric.id, runId, null);
            } catch (error) {
                // The card of the metric asks again and shows what went wrong
            }
        }));
        const held = Object.values(tickRanges)
            .filter(range => range.tickMin != null && range.tickMax != null);
        if (held.length === 0) return null;
        return {
            min: Math.min(...held.map(range => range.tickMin)),
            max: Math.max(...held.map(range => range.tickMax))
        };
    }

    /**
     * Widens the tick range of the run by what a metric turned out to hold, as a running run does
     * between two loads.
     *
     * @param {number} tickMin - First tick of a metric
     * @param {number} tickMax - Last tick of a metric
     */
    function extendRunExtent(tickMin, tickMax) {
        if (runExtent && tickMin >= runExtent.min && tickMax <= runExtent.max) return;
        runExtent = runExtent
            ? { min: Math.min(runExtent.min, tickMin), max: Math.max(runExtent.max, tickMax) }
            : { min: tickMin, max: tickMax };
        TickWindowView.show(runExtent, tickWindow);
    }

    /**
     * Takes over the tick window the reader set. The group in view loads at once; the cards of
     * the other groups load when their group is opened.
     *
     * @param {?{from: number, to: number}} next - The window, or null for the whole run
     */
    function handleTickWindowChange(next) {
        tickWindow = next;
        writeTickWindowToUrl();
        Object.values(DashboardView.getAllCards()).forEach(card => {
            card.dataRequested = false;
        });
        loadVisibleMetricsData().catch(error => {
            console.error('[AnalyzerController] Failed to load the tick window:', error);
        });
    }

    /** Writes the tick window into the URL, which keeps the run last. */
    function writeTickWindowToUrl() {
        const url = new URL(window.location.href);
        const runId = url.searchParams.get('runId');
        url.searchParams.delete('runId');
        url.searchParams.delete(WINDOW_FROM_PARAM);
        url.searchParams.delete(WINDOW_TO_PARAM);
        if (tickWindow) {
            url.searchParams.set(WINDOW_FROM_PARAM, tickWindow.from);
            url.searchParams.set(WINDOW_TO_PARAM, tickWindow.to);
        }
        if (runId) url.searchParams.set('runId', runId);
        window.history.replaceState({}, '', url.toString());
    }

export const changeRun = handleRunChange;
export const getCurrentRunId = () => currentRunId;

