
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
    
    // Message overlay (for loading, error, no-data)
    const messageOverlay = document.createElement('div');
    messageOverlay.className = 'metric-card-message-overlay';
    
    cardEl.appendChild(header);
    cardEl.appendChild(chartContainer);
    cardEl.appendChild(messageOverlay);
    
    // Store instance
    cards[metric.id] = {
        element: cardEl,
        metric: metric,
        chart: null,
        messageOverlay: messageOverlay
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
    
    if (card.chart) {
        if (card.chart.destroy) {
            card.chart.destroy();
        }
    }
    
    const chartModule = ChartRegistry.getChart(chartType);
    if (chartModule && chartModule.render) {
        card.chart = chartModule.render(canvas, data, chartConfig);
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

