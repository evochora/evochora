/**
 * Chart Utilities
 *
 * Shared utility functions for all chart types.
 *
 * @module ChartUtils
 */

/**
 * Formats large tick values with k/M suffixes for readability.
 *
 * Examples:
 *   500 → "500"
 *   1500 → "1.5k"
 *   150000 → "150k"
 *   1500000 → "1.5M"
 *   10000000 → "10M"
 *
 * @param {number} value - The tick value to format
 * @returns {string} Formatted string with appropriate suffix
 */
export function formatTickValue(value) {
    if (value === null || value === undefined) return '';

    const absValue = Math.abs(value);

    if (absValue >= 1_000_000) {
        // Millions
        const millions = value / 1_000_000;
        return millions % 1 === 0 ? `${millions}M` : `${millions.toFixed(1)}M`;
    } else if (absValue >= 1_000) {
        // Thousands
        const thousands = value / 1_000;
        return thousands % 1 === 0 ? `${thousands}k` : `${thousands.toFixed(1)}k`;
    }

    return String(value);
}

/**
 * Creates a tick callback function for Chart.js X-axis.
 * Formats large numbers with k/M suffixes.
 *
 * @returns {function} Callback function for Chart.js ticks.callback
 */
export function createTickFormatter() {
    return function(value) {
        return formatTickValue(value);
    };
}
