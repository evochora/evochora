
import * as ChartRegistry from '../charts/ChartRegistry.js';
import { formatTickValue } from '../charts/ChartUtils.js';

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
    cardEl.className = 'metric-card';
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

    // LOD chip buttons from manifest dataSources
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
        controls.appendChild(chip);
    });

    header.appendChild(titleGroup);
    header.appendChild(controls);
    
    // Chart container
    const chartContainer = document.createElement('div');
    chartContainer.className = 'metric-card-chart-container';
    chartContainer.innerHTML = `<canvas></canvas>`;
    
    // Scrollbar row: [viewFrom] [scrollbar] [viewTo]
    const scrollRow = document.createElement('div');
    scrollRow.className = 'metric-card-scrollbar-row';
    scrollRow.style.display = 'none';

    const scrollLabelFrom = document.createElement('span');
    scrollLabelFrom.className = 'metric-card-scrollbar-label';
    const scrollContainer = document.createElement('div');
    scrollContainer.className = 'metric-card-scrollbar-container';
    const scrollInner = document.createElement('div');
    scrollInner.className = 'metric-card-scrollbar-inner';
    scrollContainer.appendChild(scrollInner);
    const scrollLabelTo = document.createElement('span');
    scrollLabelTo.className = 'metric-card-scrollbar-label';

    scrollRow.appendChild(scrollLabelFrom);
    scrollRow.appendChild(scrollContainer);
    scrollRow.appendChild(scrollLabelTo);

    // Message overlay (for loading, error, no-data) — inside chart container so scrollbar stays usable
    const messageOverlay = document.createElement('div');
    messageOverlay.className = 'metric-card-message-overlay';
    chartContainer.appendChild(messageOverlay);

    cardEl.appendChild(header);
    cardEl.appendChild(chartContainer);
    cardEl.appendChild(scrollRow);

    // Store instance
    cards[metric.id] = {
        element: cardEl,
        metric: metric,
        chart: null,
        messageOverlay: messageOverlay,
        scrollRow: scrollRow,
        scrollContainer: scrollContainer,
        scrollInner: scrollInner,
        scrollLabelFrom: scrollLabelFrom,
        scrollLabelTo: scrollLabelTo
    };
    
    return cardEl;
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
 * @param {Object} card - Card instance
 * @param {Array<Object>} data - Data for the chart
 */
export function renderChart(card, data) {
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
        card.chart = chartModule.render(canvas, data, chartConfig);

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
 * Sets the active LOD level on a card's chip buttons.
 *
 * @param {Object} card - Card instance
 * @param {string} lod - LOD level to activate (e.g., 'lod0')
 */
export function setActiveLod(card, lod) {
    if (!card || !card.element) return;
    const chips = card.element.querySelectorAll('.lod-chip');
    chips.forEach(chip => {
        chip.classList.toggle('active', chip.dataset.lod === lod);
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
 * Shows the scrollbar for a card, sized proportionally to the view window.
 *
 * @param {Object} card - Card instance
 * @param {Object} windowState - { tickMin, tickMax, viewFrom, viewTo }
 */
export function showScrollbar(card, windowState) {
    if (!card || !card.scrollContainer || !card.scrollInner) return;

    const totalRange = windowState.tickMax - windowState.tickMin;
    const viewRange = windowState.viewTo - windowState.viewFrom;
    if (totalRange <= 0 || viewRange >= totalRange) {
        hideScrollbar(card);
        return;
    }

    // Store reference for live label updates during scroll
    card._windowState = windowState;

    // Show row first so layout is computed
    card.scrollRow.style.display = 'flex';

    // Update range labels
    updateScrollLabels(card, windowState.viewFrom, windowState.viewTo, viewRange);

    // Inner div width determines scrollbar thumb size relative to container
    const ratio = totalRange / viewRange;
    const containerWidth = card.scrollContainer.clientWidth || card.element.clientWidth;
    card.scrollInner.style.width = `${Math.round(containerWidth * ratio)}px`;

    // Defer scroll position to next frame so the browser has laid out the new inner width
    const scrollRatio = (windowState.viewFrom - windowState.tickMin) / (totalRange - viewRange);
    card._settingScrollPosition = true;
    requestAnimationFrame(() => {
        const maxScroll = card.scrollContainer.scrollWidth - card.scrollContainer.clientWidth;
        card.scrollContainer.scrollLeft = Math.round(scrollRatio * maxScroll);
        // Allow scroll events again after position is set
        requestAnimationFrame(() => { card._settingScrollPosition = false; });
    });
}

/**
 * Updates the from/to labels next to the scrollbar.
 */
function updateScrollLabels(card, viewFrom, viewTo, viewRange) {
    if (card.scrollLabelFrom) {
        card.scrollLabelFrom.textContent = formatTickValue(viewFrom, viewRange);
    }
    if (card.scrollLabelTo) {
        card.scrollLabelTo.textContent = formatTickValue(viewTo, viewRange);
    }
}

/**
 * Hides the scrollbar for a card.
 *
 * @param {Object} card - Card instance
 */
export function hideScrollbar(card) {
    if (card) {
        if (card.scrollRow) card.scrollRow.style.display = 'none';
        card._windowState = null;
    }
}

/**
 * Registers a debounced callback for scroll position changes on a card.
 * The callback receives a ratio (0.0 = start, 1.0 = end).
 *
 * @param {Object} card - Card instance
 * @param {function(number): void} callback - Called with scroll ratio
 */
export function setOnScroll(card, callback) {
    if (!card || !card.scrollContainer) return;

    let debounceTimer = null;
    card.scrollContainer.addEventListener('scroll', () => {
        // Ignore scroll events triggered by programmatic position updates
        if (card._settingScrollPosition) return;

        const maxScroll = card.scrollContainer.scrollWidth - card.scrollContainer.clientWidth;
        const ratio = maxScroll > 0 ? card.scrollContainer.scrollLeft / maxScroll : 0;

        // Update labels immediately for responsive feedback
        if (card._windowState) {
            const ws = card._windowState;
            const totalRange = ws.tickMax - ws.tickMin;
            const viewRange = ws.viewTo - ws.viewFrom;
            const maxOffset = totalRange - viewRange;
            const viewFrom = ws.tickMin + Math.round(ratio * maxOffset);
            const viewTo = viewFrom + viewRange;
            updateScrollLabels(card, viewFrom, viewTo, viewRange);
        }

        // Debounce the actual data fetch
        if (debounceTimer) clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => {
            callback(ratio);
        }, 150);
    });
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

