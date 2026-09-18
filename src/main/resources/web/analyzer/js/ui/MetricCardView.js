
import * as ChartRegistry from '../charts/ChartRegistry.js';

/**
 * Metric Card View
 * 
 * Manages the creation and state of individual metric cards in the dashboard.
 * Each card is a self-contained unit with its own chart and controls.
 * 
 * @module MetricCardView
 */

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

    const lodChips = document.createElement('div');
    lodChips.className = 'lod-chips';
    renderLodChips(lodChips, metric);
    controls.appendChild(lodChips);

    // Reloads this card alone; shown only while the run can still change
    const refreshButton = document.createElement('button');
    refreshButton.className = 'lod-chip card-refresh';
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
        lodChips: lodChips,
        refreshButton: refreshButton
    };
    
    return cardEl;
}

/**
 * Fills a container with one chip per level of detail the manifest entry offers.
 *
 * @param {HTMLElement} container - Element that holds the chips
 * @param {Object} metric - Metric manifest entry
 */
function renderLodChips(container, metric) {
    container.innerHTML = '';
    const lodLevels = metric.dataSources ? Object.keys(metric.dataSources).sort() : [];
    lodLevels.forEach(lod => {
        const chip = document.createElement('button');
        chip.className = 'lod-chip';
        chip.dataset.lod = lod;
        chip.textContent = lod.replace('lod', 'L');
        chip.addEventListener('click', () => {
            const card = cards[metric.id];
            if (card && card.onLodChange) {
                card.onLodChange(lod);
            }
        });
        container.appendChild(chip);
    });
}

/**
 * Takes over a manifest entry read again for a card: a running run gains levels of detail.
 *
 * @param {Object} card - Card instance
 * @param {Object} metric - Metric manifest entry
 */
export function updateMetric(card, metric) {
    if (!card || !metric) return;
    const active = card.lodChips.querySelector('.lod-chip.active');
    card.metric = metric;
    renderLodChips(card.lodChips, metric);
    if (active) setActiveLod(card, active.dataset.lod);
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
 * @param {Object} card - Card instance
 * @param {Array<Object>} data - Data for the chart
 * @param {Object} [context] - Optional render context
 * @param {Object<string, Array<Object>>|null} [context.companion] - Rows per companion metric id, if the metric has companions
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
    const chartConfig = card.metric.visualization?.config || {};

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
    }

    const chartModule = ChartRegistry.getChart(chartType);
    if (chartModule && chartModule.render) {
        card.chart = chartModule.render(canvas, data, chartConfig, context);
        if (!card.chart) {
            // A chart returns nothing when what it was given is not enough to draw from - a chart
            // grouped by a companion table, without that table. Clearing the message here would
            // leave an empty plot that reads as one still loading.
            showNoData(card);
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
 * Shows on a card's chips which level of detail it draws and how it came to it.
 *
 * @param {Object} card - Card instance
 * @param {string} lod - Level drawn (e.g., 'lod0')
 * @param {Object} [state]
 * @param {boolean} [state.pinned=false] - Whether the reader chose the level; otherwise the card
 *        chose the finest one the tick window allows
 * @param {Array<string>} [state.tooFine=[]] - Levels holding more points over the tick window
 *        than the card draws; they cannot be chosen. The level drawn may be among them: then it
 *        is the coarsest, drawn thinned, and stays enabled
 */
export function setActiveLod(card, lod, { pinned = false, tooFine = [] } = {}) {
    if (!card || !card.lodChips) return;
    card.lodChips.querySelectorAll('.lod-chip').forEach(chip => {
        const active = chip.dataset.lod === lod;
        const tooFineForWindow = tooFine.includes(chip.dataset.lod);
        chip.classList.toggle('active', active);
        chip.classList.toggle('pinned', active && pinned);
        chip.disabled = tooFineForWindow && !active;
        chip.dataset.tooltip = chip.disabled ? 'Too many points for this tick window'
            : active && tooFineForWindow ? 'Coarsest level, thinned to fit this tick window'
            : active && pinned ? 'Pinned \u2013 click to let the card choose again'
            : active ? 'Chosen for this tick window \u2013 click to pin'
            : 'Pin this level of detail';
    });
}

/**
 * Registers a callback for LOD level changes on a card.
 *
 * @param {Object} card - Card instance
 * @param {function(string): void} callback - Called with the selected LOD level
 */
export function setOnLodChange(card, callback) {
    if (card) {
        card.onLodChange = callback;
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

