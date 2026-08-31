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

/**
 * Formats an axis tick with as many decimals as its neighbours make necessary.
 *
 * How finely a value must be written follows from the distance between two ticks, not from the
 * quantity: an axis stepping by 1 needs no decimals, one stepping by 0.05 needs two. A fixed
 * choice is wrong at one end or the other - and Chart.js chooses the step from the data, so an
 * axis narrows as soon as a reader hides part of a chart's legend. Written against the step, a
 * label stays readable at every scale.
 *
 * @param {number} value - The tick value
 * @param {Array<{value: number}>} ticks - All ticks of the axis, as Chart.js passes them
 * @param {string} [suffix] - Appended to the formatted number, e.g. "%"
 * @returns {string} The formatted label
 */
export function formatAxisValue(value, ticks, suffix = '') {
    return value.toFixed(decimalsForStep(ticks)) + suffix;
}

/**
 * The number of decimals needed to tell two neighbouring ticks apart.
 *
 * @param {Array<{value: number}>} ticks - All ticks of the axis
 * @returns {number} Decimals, 0 to 6
 */
export function decimalsForStep(ticks) {
    if (!ticks || ticks.length < 2) {
        return 0;
    }

    const step = Math.abs(ticks[1].value - ticks[0].value);
    if (!(step > 0)) {
        return 0;
    }

    return Math.max(0, Math.min(6, Math.ceil(-Math.log10(step))));
}

/**
 * Tick options for a value axis of the given format.
 *
 * An integer quantity gets integer ticks from Chart.js itself, so no label is ever dropped for
 * not being whole; everything else is written with as many decimals as the tick spacing needs.
 *
 * @param {string|null} format - "integer", "percent", or anything else for a plain number
 * @returns {Object} Tick options to merge into a scale
 */
export function axisTicks(format) {
    if (format === 'integer') {
        return { precision: 0 };
    }
    const suffix = format === 'percent' ? '%' : '';
    return {
        callback: function(value, index, ticks) {
            return formatAxisValue(value, ticks, suffix);
        }
    };
}

/**
 * The title line of a tooltip: the tick the hovered point belongs to.
 *
 * Written out in full, unlike the axis below it. An axis carries ten labels side by side and
 * shortens them to "7.7M"; a tooltip describes one point and can be exact - but seven digits in a
 * row cannot be read, so they are grouped.
 *
 * @param {Array<{label: string}>} items - The hovered items, as Chart.js passes them
 * @returns {string} The title line
 */
export function tooltipTitle(items) {
    const tick = Number(items[0].label);
    return 'Tick ' + (Number.isFinite(tick) ? tick.toLocaleString('en-US') : items[0].label);
}

/**
 * A value as a tooltip states it.
 *
 * Follows the same format the axis was given, so a point reads the way the scale beside it does.
 * Where the axis shortens for want of room, this is exact; the digits are grouped either way,
 * because a bare seven-digit number is a wall.
 *
 * The grouping is fixed to one locale rather than the reader's: the same chart then reads the same
 * way for everyone looking at a run, and a number in a screenshot means what it says.
 *
 * @param {number} value - The value at the hovered point
 * @param {string|null} format - "percent", "integer", "decimal", or anything else for a plain number
 * @returns {string} The formatted value
 */
export function tooltipValue(value, format) {
    if (value === null || value === undefined || !Number.isFinite(value)) {
        return '';
    }
    if (format === 'percent') {
        return group(value, 1, 1) + '%';
    }
    if (format === 'integer') {
        return group(Math.round(value), 0, 0);
    }
    if (format === 'decimal') {
        return group(value, 2, 2);
    }
    return group(value, 0, 2);
}

/**
 * Groups a number's digits, with the given range of decimals.
 *
 * @param {number} value - The number to write
 * @param {number} min - Decimals always written
 * @param {number} max - Decimals written when they carry something
 * @returns {string} The grouped number
 */
function group(value, min, max) {
    return value.toLocaleString('en-US', {
        minimumFractionDigits: min,
        maximumFractionDigits: max
    });
}
