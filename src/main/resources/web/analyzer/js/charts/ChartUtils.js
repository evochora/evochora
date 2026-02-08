/**
 * Chart Utilities
 *
 * Shared utility functions for all chart types.
 *
 * @module ChartUtils
 */

/**
 * Formats large tick values with k/M suffixes for readability.
 * Adapts precision to the visible range so that labels remain distinguishable.
 *
 * Examples (wide range):
 *   500 → "500"
 *   1500 → "1.5k"
 *   1500000 → "1.5M"
 *
 * Examples (narrow range, e.g. 7.40M–7.45M):
 *   7400000 → "7.400M"
 *   7450000 → "7.450M"
 *
 * @param {number} value - The tick value to format
 * @param {number} [range] - The visible axis range (max - min). Used to pick precision.
 * @returns {string} Formatted string with appropriate suffix
 */
export function formatTickValue(value, range) {
    if (value === null || value === undefined) return '';

    const absValue = Math.abs(value);

    if (absValue >= 1_000_000) {
        const millions = value / 1_000_000;
        const decimals = range != null ? precisionForRange(range, 1_000_000) : (millions % 1 === 0 ? 0 : 1);
        return millions.toFixed(decimals) + 'M';
    } else if (absValue >= 1_000) {
        const thousands = value / 1_000;
        const decimals = range != null ? precisionForRange(range, 1_000) : (thousands % 1 === 0 ? 0 : 1);
        return thousands.toFixed(decimals) + 'k';
    }

    return String(value);
}

/**
 * Computes the number of decimal places needed so that tick labels
 * are distinguishable given the visible range and the divisor (1e6 or 1e3).
 *
 * @param {number} range - Visible axis range (max - min)
 * @param {number} divisor - 1_000_000 for M, 1_000 for k
 * @returns {number} Number of decimals (0–3)
 */
function precisionForRange(range, divisor) {
    const scaledRange = range / divisor;
    if (scaledRange >= 10) return 0;
    if (scaledRange >= 1)  return 1;
    if (scaledRange >= 0.1) return 2;
    return 3;
}
