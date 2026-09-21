
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

    /** How many pixels one bar and one point of a line need to stay apart on the card. */
    const PIXELS_PER_BAR = 9;
    const PIXELS_PER_LINE_POINT = 5;

    /** What a card is taken to be wide while the browser has not laid it out yet. */
    const DEFAULT_CHART_WIDTH = 800;

    /** How many resolutions a card offers, each half as fine as the one before it. */
    const RESOLUTIONS = 5;

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
     * The range the cards were last loaded over, where no tick window is set.
     *
     * A run that is being written grows under the page. The cards keep what they drew, so the
     * timeline keeps showing that stretch rather than stretching itself over ticks no chart has
     * read: the reader sees the run grow past what is on the screen, and the button that shows the
     * whole run becomes the way to fetch it.
     * @type {?{min: number, max: number}}
     */
    let loadedExtent = null;

    /** How often a run that is being written is asked how far it has come. */
    const RUN_RANGE_POLL_MS = 30000;

    /** Runs while the run shown is the one being written and the page is in front. */
    let runRangePoll = null;

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
        loadedExtent = null;
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
     * Asks the run being written how far it has come, and stops asking a run that is not.
     * <p>
     * Called whenever the run shown or the pipeline state changes, and whenever the page comes to
     * the front or leaves it: a page nobody is looking at asks nothing.
     */
export function updateRunRangePoll() {
        const state = window.footer?.pipelineState?.();
        const live = !!currentRunId && startingRunId(state) === currentRunId;
        const wanted = live && !document.hidden;
        if (wanted === !!runRangePoll) {
            return;
        }
        if (wanted) {
            runRangePoll = setInterval(pollRunRange, RUN_RANGE_POLL_MS);
        } else {
            clearInterval(runRangePoll);
            runRangePoll = null;
        }
    }

    /**
     * Takes over how far the run has come, where it has come further than the timeline says.
     *
     * Only the timeline changes: the cards keep the stretch they read, and the stretch they read
     * stays the one the timeline shows as chosen. What the run has gained since is the difference
     * between the two, which is what makes the button for the whole run something to press.
     */
    async function pollRunRange() {
        const runId = currentRunId;
        if (!runId || isLoading) {
            return;
        }
        try {
            const range = await AnalyticsApi.fetchRunRange(runId);
            if (runId !== currentRunId || range.tickMin == null || range.tickMax == null) {
                return;
            }
            const grown = !runExtent || range.tickMin < runExtent.min || range.tickMax > runExtent.max;
            if (!grown) {
                return;
            }
            runExtent = {
                min: runExtent ? Math.min(runExtent.min, range.tickMin) : range.tickMin,
                max: runExtent ? Math.max(runExtent.max, range.tickMax) : range.tickMax
            };
            TickWindowView.show(runExtent, shownWindow());
        } catch (error) {
            // A run says nothing about its range until its first files are written, and a round
            // that finds nothing changes nothing: the next one asks again
            console.debug('[AnalyzerController] The run did not say how far it has come:', error);
        }
    }

    /**
     * The stretch the timeline shows as chosen: the reader's window, or the run as far as the
     * cards have read it.
     *
     * @returns {?{from: number, to: number}} The window, or null while nothing has been read
     */
    function shownWindow() {
        if (tickWindow) {
            return tickWindow;
        }
        return loadedExtent ? { from: loadedExtent.min, to: loadedExtent.max } : null;
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
                MetricCardView.setOnResolutionChange(card, (resolution) => {
                    // A click pins a resolution; a click on the pinned one lets the card choose again
                    card.pinnedResolution = card.pinnedResolution === resolution ? null : resolution;
                    if (card.pinnedResolution != null && resolution === card.shownResolution) {
                        MetricCardView.setActiveResolution(card, resolution,
                            { pinned: true, tooFine: card.tooFine });
                        return;
                    }
                    // A resolution changes how dense the points are, not which stretch is read:
                    // a companion that does not follow the level is kept
                    loadMetricData(card, { keepCompanion: true }).catch(error => {
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

                // The screen holds several times the points a card in the dashboard does, so the
                // card reads the stretch it shows again at that density - and again on the way
                // back. Only the density changes, so a companion that does not follow the level is
                // kept: reading it again would cost seconds of the browser's one thread for a
                // table that the width of the card says nothing about
                MetricCardView.setOnFullscreenChange(card, () => {
                    loadMetricData(card, { keepCompanion: true }).catch(error => {
                        if (error.name !== 'AbortError') {
                            console.error(`[AnalyzerController] Failed to load metric ${metricId}:`, error);
                            MetricCardView.showError(card, error.message || 'Failed to load data');
                        }
                    });
                });
            }

            updateRunRangePoll();

            runExtent = await fetchRunExtent(runId, manifest.metrics);
            loadedExtent = runExtent;
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
     * @param {string} [missingTable] - Metric id of a companion table the run holds nothing for.
     *        A card drawing only from that table says which table it misses, which reads otherwise
     *        like a card whose own table is empty
     */
    function showNoDataOrRetry(card, missingTable = null) {
        const runId = currentRunId;
        const polled = window.footer?.pipelineState?.();
        const status = polled && polled.status ? polled : pipeline;
        if (!isRunStarting(status, runId)) {
            if (missingTable) {
                MetricCardView.showMissingTable(card, missingTable);
            } else {
                MetricCardView.showNoData(card);
            }
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
        // The card is measured, never the canvas: a canvas that has not been drawn into yet
        // reports the 300 pixels HTML gives it by default, and the card would then open at a
        // resolution it changes the moment the chart sizes its canvas for real
        const chartWidth = card.element.clientWidth || DEFAULT_CHART_WIDTH;
        const type = card.metric.visualization?.type || '';
        // What a reader can still tell apart: a bar needs a few pixels of its own, a line is drawn
        // from point to point and can carry many more. The card's width decides, not a number per metric
        const perPoint = type.includes('bar') ? PIXELS_PER_BAR : PIXELS_PER_LINE_POINT;
        const target = Math.max(20, Math.floor(chartWidth / perPoint));
        return Math.min(HARD_CAP, card.metric.maxDataPoints
            ? Math.min(card.metric.maxDataPoints, target) : target);
    }



    /**
     * How many moments the shown ticks hold at the finest the run was recorded with.
     *
     * A card cannot draw more points than there are moments behind them, however wide it is. Where
     * the manifest states no interval - nothing is known about what the run holds - no resolution
     * is held back.
     *
     * @param {Object} metric - Manifest entry, carrying the tick interval of every level
     * @param {number|null} from - First tick shown
     * @param {number|null} to - Last tick shown
     * @returns {number} The moments the window holds
     */
    function momentsHeld(metric, from, to) {
        const intervals = metric.tickIntervals ? Object.values(metric.tickIntervals) : [];
        if (intervals.length === 0 || from == null || to == null) {
            return Infinity;
        }
        return Math.floor((to - from) / Math.min(...intervals)) + 1;
    }

    /**
     * Picks the stored level a card reads to fill the points it draws.
     *
     * The finest level that still holds at least as many moments as the card draws: a coarser one
     * could not fill them, a finer one would only cost transfer. Where the metric says nothing
     * about its levels, the server decides.
     *
     * @param {Object} metric - Manifest entry, carrying the tick interval of every level
     * @param {number} points - How many points the card draws
     * @param {number|null} from - First tick shown
     * @param {number|null} to - Last tick shown
     * @returns {string|null} The level to read, or null to let the server choose
     */
    function finestLevelFor(metric, points, from, to) {
        const levels = metric.dataSources ? Object.keys(metric.dataSources).sort() : [];
        if (levels.length === 0 || from == null || to == null) {
            return null;
        }
        const holds = lod => (to - from) / (metric.tickIntervals?.[lod] || Infinity) + 1;
        const coarse = [...levels].reverse().find(lod => holds(lod) >= points);
        return coarse || levels[0];
    }

    /**
     * How many points a card draws at one of its resolutions.
     *
     * The finest is as many as the card can show apart - its width divided by what a bar or a
     * point of a line needs - and every further one is half of that. What the levels of the stored
     * files hold does not enter: which of them is read to fill these points is the loader's
     * business, not a choice the reader has to make.
     *
     * @param {number} capacity - How many points the card can show apart
     * @param {number} resolution - The resolution, counted from the finest
     * @returns {number} The points drawn at that resolution
     */
    function pointsAt(capacity, resolution) {
        return Math.max(2, Math.round(capacity / Math.pow(2, resolution)));
    }

    /**
     * Fills the stretch a card draws, and how many windows it cuts it into, into a query that asks
     * for them.
     *
     * A metric whose rows are counts writes one row per window of its level, and another whenever a
     * batch ends inside one, so its rows lie closer together than its window is wide. Such a query
     * says {@code {buckets}} where the number of windows belongs, and {@code {from}} and
     * {@code {to}} where the stretch does. The stretch is the page's, not the level's: a coarse
     * level holds its newest row further back, and a card reading its own rows for the answer would
     * end earlier than the card beside it.
     *
     * Where the page knows no stretch, because the metric holds no tick at all, the placeholders
     * become the first and last tick of the rows themselves - which is what the query would have
     * asked of them in any case.
     *
     * @param {string} query - The metric's query
     * @param {number} points - How many points the card draws
     * @param {?number} from - First tick drawn
     * @param {?number} to - Last tick drawn
     * @returns {string} The query with the numbers filled in
     */
    function fillWindow(query, points, from, to) {
        return query
            .replaceAll('{buckets}', String(Math.max(1, points)))
            .replaceAll('{from}', from != null ? String(from) : 'SELECT MIN(tick) FROM {table}')
            .replaceAll('{to}', to != null ? String(to) : 'SELECT MAX(tick) FROM {table}');
    }

    /**
     * Brings the loaded rows down to what the card draws.
     *
     * Rows of ticks are dropped where there are more than the card can show. A metric whose
     * columns are counts may not be treated that way: a dropped row takes its counts with it, and
     * the card would say that fewer were born than were. Such a metric has to add its counts up
     * per window in its own query, and where it did not, the card refuses rather than undercount.
     *
     * @param {Array<Object>} rows - The rows as loaded
     * @param {number} limit - How many ticks the card draws
     * @param {Object} metric - Manifest entry, saying which of its columns are counts
     * @returns {Array<Object>} The rows the card draws
     * @throws {Error} If counts would have to be dropped
     */
    function fitToCard(rows, limit, metric) {
        const thinned = thinToLimit(rows, limit);
        if (thinned.length < rows.length && metric.summedColumns?.length > 0) {
            throw new Error(
                `Metric ${metric.id} holds counts in ${metric.summedColumns.join(', ')} and `
                + `returned ${rows.length} rows for a card drawing ${limit}: its query has to add `
                + `them up per window, since dropping rows would drop what they counted`);
        }
        return thinned;
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
            // A card that derives its rows holds no table of its own: it names no query, no level
            // and no tick range, and everything it draws comes from its companions
            const derived = !!metric.visualization?.config?.derived;
            const hasGeneratedQuery = metric.generatedQuery && metric.generatedQuery.trim();
            const isParquet = !!hasGeneratedQuery;
            const effectiveLimit = calculateEffectiveLimit(card);

            // Phase 1: which ticks the metric holds
            const rangeInfo = derived
                ? { tickMin: null, tickMax: null, lod: null }
                : tickRanges[metricId]
                    ?? (tickRanges[metricId] =
                        await AnalyticsApi.fetchTickRange(metricId, currentRunId, null));
            const tickMin = rangeInfo.tickMin;
            const tickMax = rangeInfo.tickMax;
            const hasRange = tickMin != null && tickMax != null;

            // Every card covers the same stretch, the one the timeline shows: a moment found in one
            // chart is looked at in all of them. A metric that holds less of it ends where its rows
            // end, and one that holds more is cut to it rather than reaching further than the rest
            const shown = shownWindow();
            const from = hasRange ? Math.max(tickMin, shown ? shown.from : tickMin) : null;
            const to = hasRange ? Math.min(tickMax, shown ? shown.to : tickMax) : null;
            if (hasRange && from > to) {
                MetricCardView.showNoData(card);
                return;
            }

            // Phase 2: the resolution. The reader picks how many points are drawn; the finest is
            // as many as the card can show apart, and each further one is half of that. Which of
            // the stored levels is read to fill them is decided below, not by the reader
            const resolutions = MetricCardView.resolutionsOf();
            // A resolution asking for more points than the window holds shows nothing finer than
            // the one below it, so it is offered greyed out rather than as a choice without effect
            const held = momentsHeld(metric, from, to);
            const tooFine = resolutions.filter(step => pointsAt(effectiveLimit, step) > held);
            if (tooFine.includes(card.pinnedResolution)) {
                card.pinnedResolution = null;
            }
            const resolution = card.pinnedResolution ?? Math.max(0,
                resolutions.findIndex(step => !tooFine.includes(step)));
            const points = pointsAt(effectiveLimit, resolution);
            // The finest stored level that still holds more moments than the card draws; a coarser
            // one would have to be stretched, a finer one only costs transfer
            const storageLevel = derived ? null : finestLevelFor(metric, points, from, to);
            // Only the files the shown stretch reaches into are fetched, whether the reader set a
            // window or is looking at the whole run: what lies past it is drawn by no card
            const viewFrom = from;
            const viewTo = to;

            // Phase 3: fetch the data of the window
            let data;
            if (derived) {
                data = [];
            } else if (isParquet) {
                const { blob: parquetBlob } = await AnalyticsApi.fetchParquetBlob(
                    metricId, currentRunId, storageLevel, controller.signal, viewFrom, viewTo
                );
                const blobKey = `${metricId}_${storageLevel || 'auto'}`;
                await DuckDBClient.registerParquetBlob(blobKey, parquetBlob);
                // A metric whose rows carry a second dimension is read column by column: the same
                // values, without an object per row (see ManifestEntry.columnar)
                const query = fillWindow(metric.generatedQuery, points, from, to);
                data = metric.columnar
                    ? await DuckDBClient.queryRegisteredBlobColumns(blobKey, query)
                    : await DuckDBClient.queryRegisteredBlob(blobKey, query);
            } else {
                const result = await AnalyticsApi.queryData(
                    currentRunId, metricId, storageLevel, controller.signal, viewFrom, viewTo
                );
                data = result.data;
            }

            card.shownResolution = resolution;
            card.tooFine = tooFine;
            MetricCardView.setActiveResolution(card, resolution,
                { pinned: card.pinnedResolution != null, tooFine });

            if (!derived && data.length === 0) {
                showNoDataOrRetry(card);
                return;
            }

            const previous = loadedData[metricId];
            // A companion that follows the level was read from the level this card read, so it is
            // kept only while that stays the same - the resolution says nothing about it
            const followsLevel = (metric.companions || []).some(companion => companion.followsLevel);
            const companionKept = keepCompanion && previous?.companion
                && (!followsLevel || previous.storageLevel === storageLevel);
            const companion = companionKept
                ? { rows: previous.companion, missing: previous.missing }
                : await loadCompanionData(metric, storageLevel, controller.signal);
            loadedData[metricId] = {
                data: fitToCard(data, points, metric),
                companion: companion ? companion.rows : null,
                missing: companion ? companion.missing : [],
                storageLevel,
                points,
                window: tickWindow
            };
            renderWithViewState(card);

        } catch (error) {
            if (error.name === 'AbortError') {
                throw error;
            }
            if (error.code === 'NO_DATA') {
                showNoDataOrRetry(card, error.missingTable);
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
     * A companion the manifest marks as columnar arrives as one array of values per selected column
     * instead of one object per row, because a table of hundreds of thousands of rows costs more in
     * row objects than in the values they carry. Its entry in the result is that column object,
     * keyed by column name, in place of the array of rows every other companion's entry holds; what
     * the types of those values are stands at {@link DuckDBClient.queryRegisteredBlobColumns}.
     *
     * @param {Object} metric - Manifest entry of the metric being loaded
     * @param {string|null} lod - Level of detail the chart shows, for companions following it
     * @param {AbortSignal} signal - Signal aborting the fetch
     * @returns {Promise<{rows: Object<string, Array<Object>|Object<string, ArrayLike<*>>>,
     *          missing: Array<string>}|null>} Per companion metric id its rows, or its columns
     *          where the companion is columnar, and the metric ids of the tables that hold nothing
     *          at this level; null if the metric has no companions
     */
    async function loadCompanionData(metric, lod, signal) {
        if (!metric.companions || metric.companions.length === 0) {
            return null;
        }

        const rowsByMetric = {};
        const missing = [];
        for (const companion of metric.companions) {
            const blobKey = `companion_${metric.id}_${companion.metricId}`;
            const level = companion.followsLevel && lod ? lod : 'lod0';
            let blob;
            try {
                ({ blob } = await AnalyticsApi.fetchParquetBlob(
                    companion.metricId, currentRunId, level, signal
                ));
            } catch (error) {
                // A table that holds nothing at this level is not an error of the card: a table
                // written only now and then has no coarse level yet while one written every
                // recording does. The card draws what it has, and says which table it misses only
                // where that leaves it with nothing to draw
                if (error.code !== 'NO_DATA') {
                    throw error;
                }
                missing.push(companion.metricId);
                continue;
            }
            await DuckDBClient.registerParquetBlob(blobKey, blob);
            rowsByMetric[companion.metricId] = companion.columnar
                ? await DuckDBClient.queryRegisteredBlobColumns(blobKey, companion.query)
                : await DuckDBClient.queryRegisteredBlob(blobKey, companion.query);
        }
        return { rows: rowsByMetric, missing };
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
            missing: loaded.missing,
            window: loaded.window,
            points: loaded.points,
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
     * Takes over the tick window the reader set. The group in view loads at once; the cards of
     * the other groups load when their group is opened.
     *
     * @param {?{from: number, to: number}} next - The window, or null for the whole run
     */
    function handleTickWindowChange(next) {
        tickWindow = next;
        // What the cards are about to read is the run as far as it has come, so the timeline shows
        // the whole of it as chosen until the next round finds it has come further
        loadedExtent = runExtent;
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

