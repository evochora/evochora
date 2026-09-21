
import * as ChartRegistry from '../charts/ChartRegistry.js';
import * as GenerationTimeSeries from '../charts/GenerationTimeSeries.js';
import * as MutationSuccessSeries from '../charts/MutationSuccessSeries.js';

/**
 * Metric Card View
 * 
 * Manages the creation and state of individual metric cards in the dashboard.
 * Each card is a self-contained unit with its own chart and controls.
 * 
 * @module MetricCardView
 */

/**
 * The derivations a card can name under {@code derived} in its visualization config, by that name.
 * A derivation becomes available to cards by being added here.
 *
 * Such a card draws nothing from its own rows. Its manifest entry names a companion table, and the
 * derivation computes every row the chart draws from that table alone; the chart sees ordinary rows
 * and knows nothing of where they came from. The registry lives here, where the rows are handed to
 * the chart, because charts of different kinds draw derived rows and each of them would otherwise
 * carry the same map.
 *
 * A module registered here exports {@code derive(companion, config)}:
 * <ul>
 *   <li>{@code companion} - what the card loaded for its companions: per companion metric id its
 *       rows, or, where the manifest marks the companion as columnar, one array of values per
 *       column keyed by column name. It is null for a metric without companions.</li>
 *   <li>{@code config} - the visualization config, which names the companion metric ids the
 *       derivation reads and everything else it needs.</li>
 *   <li>{@code window} - the ticks the card shows, as {@code {from, to}}, or null for the whole
 *       run. What a derivation computes over the whole table it still draws for this window only,
 *       so that every card of the dashboard covers the same ticks.</li>
 *   <li>{@code points} - how many pieces to cut the shown ticks into, which is what the reader
 *       chose as the card's resolution.</li>
 *   <li>returns the rows the chart draws, in the shape a chart's own rows have: an array of objects
 *       carrying the x key and the y keys the config names.</li>
 * </ul>
 *
 * A derivation whose companion is missing or empty returns an empty array rather than throwing; the
 * card then says it has no data, as a card whose own table is empty does.
 */
const DERIVATIONS = {
    'generation-time': GenerationTimeSeries,
    'mutation-success': MutationSuccessSeries
};

// State
let cards = {}; // Store card instances by metric ID

/**
 * Clears all card instances and destroys their charts.
 * Should be called before creating a new set of cards.
 */
export function reset() {
    for (const id of Object.keys(cards)) {
        const card = cards[id];
        if (card.chart && card.chart.destroy) {
            card.chart.destroy();
        }
    }
    cards = {};
}

/**
 * Creates a new metric card element.
 * 
 * @param {Object} metric - Metric manifest entry
 * @returns {HTMLElement} The created card element
 */
export function create(metric) {
    const cardEl = document.createElement('div');
    cardEl.className = metric.fullWidth ? 'metric-card full-width' : 'metric-card';
    cardEl.dataset.metricId = metric.id;
    
    // Header
    const header = document.createElement('div');
    header.className = 'metric-card-header';

    const titleGroup = document.createElement('div');
    titleGroup.className = 'metric-card-title-group';
    titleGroup.innerHTML = `
        <h3 class="metric-card-title">${metric.name}</h3>
        <p class="metric-card-description">${metric.description || ''}</p>
    `;

    const controls = document.createElement('div');
    controls.className = 'metric-card-controls';

    const resolutionChips = document.createElement('div');
    resolutionChips.className = 'resolution-chips';
    renderResolutionChips(resolutionChips, metric);
    controls.appendChild(resolutionChips);

    // Reloads this card alone; shown only while the run can still change
    const refreshButton = document.createElement('button');
    refreshButton.className = 'resolution-chip card-refresh';
    refreshButton.textContent = '\u27F3';
    refreshButton.setAttribute('aria-label', 'Reload this card');
    refreshButton.dataset.tooltip = 'Reload this card';
    refreshButton.hidden = true;
    refreshButton.addEventListener('click', () => {
        const card = cards[metric.id];
        if (card && card.onRefresh) {
            card.onRefresh();
        }
    });
    controls.appendChild(refreshButton);

    header.appendChild(titleGroup);
    header.appendChild(controls);
    
    // Chart container
    const chartContainer = document.createElement('div');
    chartContainer.className = 'metric-card-chart-container';
    chartContainer.innerHTML = `<canvas></canvas>`;
    
    // Message overlay (for loading, error, no-data) — inside chart container over the chart alone
    const messageOverlay = document.createElement('div');
    messageOverlay.className = 'metric-card-message-overlay';
    chartContainer.appendChild(messageOverlay);

    cardEl.appendChild(header);
    cardEl.appendChild(chartContainer);

    // Store instance
    cards[metric.id] = {
        element: cardEl,
        metric: metric,
        chart: null,
        messageOverlay: messageOverlay,
        resolutionChips: resolutionChips,
        refreshButton: refreshButton
    };
    
    return cardEl;
}

/**
 * Fills a container with one chip per resolution the card offers.
 *
 * @param {HTMLElement} container - Element that holds the chips
 * @param {Object} metric - Metric manifest entry
 */
function renderResolutionChips(container, metric) {
    container.innerHTML = '';
    resolutionsOf().forEach(resolution => {
        const chip = document.createElement('button');
        chip.className = 'resolution-chip';
        chip.dataset.resolution = resolution;
        chip.textContent = 'L' + resolution;
        chip.addEventListener('click', () => {
            const card = cards[metric.id];
            if (card && card.onResolutionChange) {
                card.onResolutionChange(resolution);
            }
        });
        container.appendChild(chip);
    });
}

/** The resolutions a card offers, the finest first, counted from it. */
const RESOLUTIONS = [0, 1, 2, 3, 4];

/**
 * The resolutions a card offers.
 *
 * Every card offers the same ones: the finest draws as many points as the card can show apart, and
 * each further one halves that. Which stored files are read to fill them is the loader's business,
 * so a card that derives its rows offers the same choice as one drawn from its own table.
 *
 * @returns {Array<number>} The resolutions, finest first
 */
export function resolutionsOf() {
    return RESOLUTIONS;
}


/**
 * Takes over a manifest entry read again for a card: a running run gains levels of detail.
 *
 * @param {Object} card - Card instance
 * @param {Object} metric - Metric manifest entry
 */
export function updateMetric(card, metric) {
    if (!card || !metric) return;
    const active = card.resolutionChips.querySelector('.resolution-chip.active');
    card.metric = metric;
    renderResolutionChips(card.resolutionChips, metric);
    if (active) {
        setActiveResolution(card, Number(active.dataset.resolution),
            { pinned: !!card.pinnedResolution, tooFine: card.tooFine || [] });
    }
}

/**
 * Registers the callback of a card's reload button.
 *
 * @param {Object} card - Card instance
 * @param {function(): void} callback
 */
export function setOnRefresh(card, callback) {
    if (card) {
        card.onRefresh = callback;
    }
}

/**
 * Shows or hides the reload button of a card.
 *
 * @param {Object} card - Card instance
 * @param {boolean} visible
 */
export function setRefreshVisible(card, visible) {
    if (card && card.refreshButton) {
        card.refreshButton.hidden = !visible;
    }
}

/**
 * Enables or disables the reload button of a card, as while the card loads.
 *
 * @param {Object} card - Card instance
 * @param {boolean} enabled
 */
export function setRefreshEnabled(card, enabled) {
    if (card && card.refreshButton) {
        card.refreshButton.disabled = !enabled;
    }
}

/**
 * Gets all card instances.
 * 
 * @returns {Object<string, Object>}
 */
export function getAllCards() {
    return cards;
}

/**
 * Renders a chart inside a metric card.
 *
 * The context carries what a chart needs beyond its own rows. A chart that shows one fixed thing
 * ignores it; one that lets the reader choose what it shows reads its choice from
 * {@code viewState} and asks for a new one through {@code onViewStateChange}, which redraws from
 * the rows already loaded.
 *
 * A card whose visualization config names a derivation under {@code derived} hands the chart the
 * rows that derivation returns instead of its own; see {@link DERIVATIONS}.
 *
 * @param {Object} card - Card instance
 * @param {Array<Object>} data - Data for the chart
 * @param {Object} [context] - Optional render context
 * @param {Object<string, Array<Object>|Object<string, ArrayLike<*>>>|null} [context.companion] -
 *        Per companion metric id its rows, or its columns where the companion is columnar, if the
 *        metric has companions
 * @param {Array<string>} [context.missing] - Companion tables that held nothing at this level
 * @param {{from: number, to: number}|null} [context.window] - The ticks the card shows
 * @param {number|null} [context.points] - How many points the card draws
 * @param {Object|null} [context.viewState] - The view state this chart last asked for
 * @param {Function} [context.onViewStateChange] - Called with a new view state to redraw
 *
 * A chart module that cannot draw from what it was given returns nothing, and the card says so
 * instead of showing an empty plot.
 */
export function renderChart(card, data, context = {}) {
    if (!card) return;

    const canvas = card.element.querySelector('canvas');
    const chartType = card.metric.visualization?.type;
    // The chart is told what the card is called, so that a series without a name of its own can
    // say which quantity it belongs to rather than only which percentile it is
    const chartConfig = { metricName: card.metric.name, ...(card.metric.visualization?.config || {}) };

    // Capture hidden dataset labels before destroying
    const hiddenLabels = new Set();
    if (card.chart?.data?.datasets) {
        card.chart.data.datasets.forEach((ds, idx) => {
            if (card.chart.getDatasetMeta(idx).hidden) {
                hiddenLabels.add(ds.label);
            }
        });
    }

    if (card.chart) {
        if (card.chart.destroy) {
            card.chart.destroy();
        }
        card.chart = null;
    }

    let rows = data;
    if (chartConfig.derived) {
        const derivation = DERIVATIONS[chartConfig.derived];
        if (!derivation) {
            console.warn(`[MetricCardView] Unknown derivation: ${chartConfig.derived}`);
            showError(card, `Unknown derivation: ${chartConfig.derived}`);
            return;
        }
        rows = derivation.derive(context.companion || null, chartConfig,
            context.window || null, context.points || null);
        if (!rows || rows.length === 0) {
            showNothingToDraw(card, context);
            return;
        }
    }

    const chartModule = ChartRegistry.getChart(chartType);
    if (chartModule && chartModule.render) {
        card.chart = chartModule.render(canvas, rows, chartConfig, context);
        if (!card.chart) {
            // A chart returns nothing when what it was given is not enough to draw from - a chart
            // grouped by a companion table, without that table. Clearing the message here would
            // leave an empty plot that reads as one still loading.
            showNothingToDraw(card, context);
            return;
        }

        // Restore hidden state for matching labels
        if (hiddenLabels.size > 0 && card.chart?.data?.datasets) {
            card.chart.data.datasets.forEach((ds, idx) => {
                if (hiddenLabels.has(ds.label)) {
                    card.chart.getDatasetMeta(idx).hidden = true;
                }
            });
            card.chart.update('none');
        }

        hideMessage(card);
    } else {
        console.warn(`[MetricCardView] Unknown chart type: ${chartType}`);
        showError(card, `Unknown chart type: ${chartType}`);
    }
}

/**
 * Shows a loading state on the card.
 */
export function showLoading(card) {
    if (card) {
        showMessage(card, 'Loading...');
    }
}

/**
 * Shows an error state on the card.
 */
export function showError(card, message) {
    if (card) {
        showMessage(card, `Error: ${message}`, true);
    }
}

/**
 * Shows on a card's chips which resolution it draws and how it came to it.
 *
 * @param {Object} card - Card instance
 * @param {number} resolution - The resolution drawn, counted from the finest
 * @param {Object} [state]
 * @param {boolean} [state.pinned=false] - Whether the reader chose it; otherwise the card chose
 *        the finest one the run fills over the ticks shown
 * @param {Array<number>} [state.tooFine=[]] - Resolutions asking for more points than the run
 *        holds over the ticks shown; they cannot be chosen. The one drawn may be among them: then it
 *        is the coarsest, drawn thinned, and stays enabled
 */
export function setActiveResolution(card, resolution, { pinned = false, tooFine = [] } = {}) {
    if (!card || !card.resolutionChips) return;
    card.resolutionChips.querySelectorAll('.resolution-chip').forEach(chip => {
        const shown = Number(chip.dataset.resolution);
        const active = shown === resolution;
        const tooFineForWindow = tooFine.includes(shown);
        chip.classList.toggle('active', active);
        chip.classList.toggle('pinned', active && pinned);
        chip.disabled = tooFineForWindow && !active;
        chip.dataset.tooltip = chip.disabled
                ? 'Finer than this run was recorded over the ticks shown'
            : active && pinned ? 'Pinned \u2013 click to let the card choose again'
            : active ? 'Finest resolution this card can show \u2013 click to pin'
            : 'Pin this resolution';
    });
}

/**
 * Registers a callback for resolution level changes on a card.
 *
 * @param {Object} card - Card instance
 * @param {function(string): void} callback - Called with the selected resolution level
 */
export function setOnResolutionChange(card, callback) {
    if (card) {
        card.onResolutionChange = callback;
    }
}

/**
 * Shows a no-data state on the card.
 */
export function showNoData(card) {
    if (card) {
        showMessage(card, 'No data available');
    }
}

/**
 * Shows that the card has nothing to draw, naming the table it missed where one was missing.
 *
 * @param {Object} card - Card instance
 * @param {Object} context - Render context, carrying the tables that held nothing
 */
function showNothingToDraw(card, context) {
    const missing = context && context.missing;
    if (missing && missing.length > 0) {
        showMissingTable(card, missing[0]);
        return;
    }
    showNoData(card);
}

/**
 * Shows that the card needs a table the run does not hold.
 *
 * @param {Object} card - Card instance
 * @param {string} table - Metric id of the table the card draws from
 */
export function showMissingTable(card, table) {
    if (card) {
        showMessage(card, `Needs the table '${table}', which this run does not hold.`);
    }
}

/**
 * Shows that the card has no data yet but may still get some.
 *
 * @param {Object} card
 */
export function showWaitingForData(card) {
    if (card) {
        showMessage(card, 'Waiting for data\u2026');
    }
}

/**
 * Shows a message on the card overlay.
 * 
 * @param {Object} card
 * @param {string} message
 * @param {boolean} [isError=false]
 */
function showMessage(card, message, isError = false) {
    if (!card || !card.messageOverlay) return;
    
    card.messageOverlay.textContent = message;
    card.messageOverlay.classList.add('visible');
    if (isError) {
        card.messageOverlay.classList.add('error');
    } else {
        card.messageOverlay.classList.remove('error');
    }
}

/**
 * Hides the message overlay.
 */
function hideMessage(card) {
    if (card && card.messageOverlay) {
        card.messageOverlay.classList.remove('visible');
    }
}

