import * as MetricCardView from './MetricCardView.js';

/**
 * Dashboard View
 * 
 * Manages the main dashboard grid, creating and holding metric cards.
 * 
 * @module DashboardView
 */

let container = null;

/**
 * Initializes the dashboard view.
 */
export function init() {
    container = document.getElementById('dashboard-container');
    if (!container) {
        console.error('[DashboardView] Dashboard container not found');
    }
}

/**
 * Clears the dashboard and creates metric cards from a manifest.
 * 
 * @param {Array<Object>} metrics - Metric manifest entries
 */
export function createCards(metrics) {
    if (!container) return;
    
    MetricCardView.reset(); // <-- NEU: State leeren bevor neue Cards erstellt werden
    container.innerHTML = ''; // Clear previous DOM
    
    const dashboard = document.createElement('div');
    dashboard.className = 'dashboard';
    
    metrics.forEach(metric => {
        const card = MetricCardView.create(metric);
        dashboard.appendChild(card);
    });
    
    container.appendChild(dashboard);
}

/**
 * Gets all metric card instances from the view.
 * 
 * @returns {Object<string, Object>} Map of metric ID to card instance
 */
export function getAllCards() {
    return MetricCardView.getAllCards();
}

/**
 * Displays a message in the dashboard area (e.g., loading, error).
 * 
 * @param {string} message
 * @param {boolean} [isError=false]
 */
export function showMessage(message, isError = false) {
    if (!container) return;
    
    container.innerHTML = `
        <div class="message ${isError ? 'error' : ''}">${message}</div>
    `;
}

/**
 * Displays an empty state message (no data for run).
 */
export function showEmptyState(message) {
    if (!container) return;
    
    container.innerHTML = `
        <div class="empty-state">
            <h2>No Data</h2>
            <p>${message}</p>
        </div>
    `;
}

