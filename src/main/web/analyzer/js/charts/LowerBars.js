import { tooltipValue } from './ChartUtils.js';

/**
 * Lower Bars
 *
 * A second group of stacked bars drawn below a stacked bar chart, on an axis of its own that runs
 * downwards in absolute numbers, fed from a companion table rather than from the chart's own rows.
 *
 * The visualization config describes the group under `lower`:
 * - `metric` - companion metric id holding the rows
 * - `group` - column naming the bar segment a row belongs to
 * - `detail` - column whose values the tooltip of a segment lists with their counts
 * - `value` - column holding the count of a row
 * - `bucketSize` - column of the chart's own rows holding the bucket size its query used, so the
 *   companion rows, written per recording, fall into the same buckets
 * - `label` - axis title
 * - `maxGroups` - largest number of named segments; the rest is one segment
 *
 * The companion rows arrive as written; summing them happens here, because grouping by a text
 * column is a hash aggregation the browser's DuckDB build does not survive.
 *
 * @module LowerBars
 */

/** Name of the segment collecting the groups too small to get one of their own. */
const OTHER = 'other';

/** Largest number of detail lines a segment's tooltip lists before summing up the rest. */
const MAX_DETAIL_LINES = 10;

/** Colors of the segments, from the largest group down; the collected rest is grey. */
const COLORS = ['#ff6b6b', '#ff8e72', '#ffa07a', '#f4845f', '#e76f51', '#d62828', '#c1121f', '#9d0208'];
const OTHER_COLOR = '#6c757d';

/** Axis id the lower bars are drawn on. */
export const AXIS_ID = 'yLower';

function toNumber(value) {
    return typeof value === 'bigint' ? Number(value) : Number(value ?? 0);
}

function addDetail(details, index, key, value) {
    let map = details[index];
    if (!map) {
        map = new Map();
        details[index] = map;
    }
    map.set(key, (map.get(key) || 0) + value);
}

/**
 * Sums the companion rows into one segment per group and bucket.
 *
 * @param {Array<Object>} data - The chart's own rows, one per bucket
 * @param {string} xKey - Column of the chart's rows holding the bucket's tick
 * @param {Object<string, Array<Object>>|null} companion - Rows per companion metric id
 * @param {Object} spec - The `lower` part of the visualization config
 * @returns {Array<{label: string, color: string, values: number[], details: Array<Map<string, number>>}>|null}
 *          Segments from the largest to the collected rest, or null without the companion
 */
export function build(data, xKey, companion, spec) {
    const rows = spec ? companion?.[spec.metric] : null;
    if (!rows) {
        return null;
    }

    const index = new Map(data.map((row, i) => [toNumber(row[xKey]), i]));
    const size = toNumber(data[0]?.[spec.bucketSize]) || 1;
    const groups = new Map();

    for (const row of rows) {
        const bucket = Math.floor(toNumber(row.tick) / size) * size;
        const i = index.get(bucket);
        if (i === undefined) continue;
        const value = toNumber(row[spec.value]);
        const name = String(row[spec.group]);
        let group = groups.get(name);
        if (!group) {
            group = { label: name, total: 0, values: new Array(data.length).fill(0), details: [] };
            groups.set(name, group);
        }
        group.total += value;
        group.values[i] += value;
        addDetail(group.details, i, String(row[spec.detail]), value);
    }

    const ranked = [...groups.values()].sort((a, b) => b.total - a.total);
    const named = ranked.slice(0, spec.maxGroups || COLORS.length);
    const rest = ranked.slice(named.length);

    const segments = named.map((group, k) => ({
        label: group.label,
        color: COLORS[k % COLORS.length],
        values: group.values,
        details: group.details
    }));

    if (rest.length > 0) {
        const other = { label: OTHER, color: OTHER_COLOR, values: new Array(data.length).fill(0), details: [] };
        for (const group of rest) {
            group.values.forEach((value, i) => { other.values[i] += value; });
            group.details.forEach((map, i) => {
                for (const [key, value] of map) {
                    addDetail(other.details, i, `${group.label}: ${key}`, value);
                }
            });
        }
        segments.push(other);
    }
    return segments;
}

/**
 * The tooltip lines of one segment: its total, then its details from the most frequent down.
 *
 * @param {string} label - Segment name
 * @param {number} total - Count of the segment in the bucket
 * @param {Map<string, number>|undefined} details - Counts per detail text in the bucket
 * @returns {string[]} Lines
 */
export function tooltipLines(label, total, details) {
    const lines = [`${label} — ${tooltipValue(total, 'integer')} failures`];
    const sorted = [...(details || new Map()).entries()].sort((a, b) => b[1] - a[1]);
    for (const [text, count] of sorted.slice(0, MAX_DETAIL_LINES)) {
        lines.push(`  ${text}  ×${tooltipValue(count, 'integer')}`);
    }
    const hidden = sorted.slice(MAX_DETAIL_LINES);
    if (hidden.length > 0) {
        const sum = hidden.reduce((acc, [, count]) => acc + count, 0);
        lines.push(`  + ${hidden.length} more messages  ×${tooltipValue(sum, 'integer')}`);
    }
    return lines;
}

/**
 * Tells whether a tooltip item belongs to the segment under the pointer.
 *
 * @param {Object} item - Chart.js tooltip item of a lower dataset
 * @param {number} y - Pointer position in canvas pixels
 * @returns {boolean}
 */
export function isUnderPointer(item, y) {
    const element = item.element;
    if (!element) return false;
    const top = Math.min(element.y, element.base);
    const bottom = Math.max(element.y, element.base);
    return y >= top && y <= bottom && top !== bottom;
}

/**
 * Draws the legend of the lower bars in its own row under the chart, each entry toggling its
 * segment. Laid over the canvas it would cover the axis.
 *
 * @param {Chart} chart - Chart.js instance
 * @returns {HTMLElement|null} The legend row, or null if the chart is not in a card
 */
export function renderLegend(chart) {
    const container = chart.canvas.parentElement;
    const card = container?.parentElement;
    if (!card) return null;

    let row = card.querySelector('.lower-legend');
    if (!row) {
        row = document.createElement('div');
        row.className = 'lower-legend';
        container.insertAdjacentElement('afterend', row);
    }
    row.replaceChildren();

    chart.data.datasets.forEach((dataset, index) => {
        if (dataset.yAxisID !== AXIS_ID) return;
        const entry = document.createElement('button');
        entry.type = 'button';
        entry.className = 'lower-legend-item';
        entry.classList.toggle('hidden', !chart.isDatasetVisible(index));
        const swatch = document.createElement('span');
        swatch.className = 'lower-legend-swatch';
        swatch.style.background = dataset.borderColor;
        entry.append(swatch, document.createTextNode(dataset.label));
        entry.addEventListener('click', () => {
            chart.setDatasetVisibility(index, !chart.isDatasetVisible(index));
            entry.classList.toggle('hidden', !chart.isDatasetVisible(index));
            chart.update();
        });
        row.append(entry);
    });
    return row;
}

/**
 * Removes the legend row of a chart, as when the chart is drawn without lower bars.
 *
 * @param {HTMLCanvasElement} canvas - Canvas of the chart
 */
export function removeLegend(canvas) {
    canvas?.closest('.metric-card')?.querySelector('.lower-legend')?.remove();
}
