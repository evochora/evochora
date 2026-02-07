import * as ChartRegistry from './ChartRegistry.js';
import { formatTickValue } from './ChartUtils.js';

/**
 * Stacked Area Chart Implementation
 * 
 * Renders time-series data as stacked area charts using Chart.js.
 * This is useful for showing how part-to-whole relationships change over time.
 * 
 * @module StackedAreaChart
 */
    
    // Evochora color palette
    const COLORS = [
        '#4a9eff', '#a0e0a0', '#ffb366', '#dda0dd', '#87ceeb', 
        '#ffd700', '#ff6b6b', '#98d8c8', '#f08080', '#c79ecf'
    ];
    
    function getColor(index) {
        return COLORS[index % COLORS.length];
    }
    
    function toNumber(value) {
        if (typeof value === 'bigint') {
            return Number(value);
        }
        return value;
    }

function formatLabel(key) {
    return key.split('_').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

/**
 * Creates a legend click handler that adjusts Y-axis max.
 * When all categories visible: max = 100
 * When some hidden: max = undefined (auto-scale)
 */
function createLegendClickHandler() {
    return function(e, legendItem, legend) {
        const chart = legend.chart;
        const index = legendItem.datasetIndex;
        
        // Toggle visibility (default behavior)
        const meta = chart.getDatasetMeta(index);
        meta.hidden = meta.hidden === null ? !chart.data.datasets[index].hidden : null;
        
        // Count visible datasets
        let visibleCount = 0;
        chart.data.datasets.forEach((ds, idx) => {
            const dsMeta = chart.getDatasetMeta(idx);
            if (!dsMeta.hidden) {
                visibleCount++;
            }
        });
        
        // Adjust Y-axis max
        if (visibleCount === chart._totalDatasets && !chart._hasExternalBase) {
            // All visible and no external percentBase: fix at 100%
            chart.options.scales.y.max = 100;
        } else {
            // Some hidden or external percentBase: auto-scale
            chart.options.scales.y.max = undefined;
        }
        
        chart.update();
    };
}
    
/**
 * Pivots long-format data into wide format for stacked chart.
 *
 * Long format: [{tick: 1, label: 'A', count: 5}, {tick: 1, label: 'B', count: 3}, ...]
 * Wide format: [{tick: 1, A: 5, B: 3}, ...]
 *
 * @param {Array<Object>} data - Long format data
 * @param {string} xKey - X-axis column (e.g., 'tick')
 * @param {string} groupKey - Grouping column (e.g., 'genome_label')
 * @param {string} valueKey - Value column (e.g., 'count')
 * @returns {{pivoted: Array<Object>, groups: string[]}} Pivoted data and group labels
 */
function pivotData(data, xKey, groupKey, valueKey) {
    // Collect unique x values and groups
    const xValues = [...new Set(data.map(row => toNumber(row[xKey])))].sort((a, b) => a - b);
    const groups = [...new Set(data.map(row => row[groupKey]))];

    // Build lookup: x -> group -> value
    const lookup = new Map();
    for (const row of data) {
        const x = toNumber(row[xKey]);
        const group = row[groupKey];
        const value = toNumber(row[valueKey]) || 0;

        if (!lookup.has(x)) {
            lookup.set(x, new Map());
        }
        lookup.get(x).set(group, value);
    }

    // Create pivoted rows
    const pivoted = xValues.map(x => {
        const row = { [xKey]: x };
        const groupValues = lookup.get(x) || new Map();
        for (const group of groups) {
            row[group] = groupValues.get(group) || 0;
        }
        return row;
    });

    return { pivoted, groups };
}

/**
 * Expands JSON column data into wide format for stacked chart.
 *
 * Input: [{tick: 1, genome_data: '{"a3Bf2k":42,"other":5}'}, ...]
 * Output: [{tick: 1, a3Bf2k: 42, other: 5}, ...]
 *
 * Groups are collected across all rows so that every row has every group key (defaulting to 0).
 *
 * @param {Array<Object>} data - Data with JSON-encoded column
 * @param {string} xKey - X-axis column (e.g., 'tick')
 * @param {string} jsonKey - Column containing JSON map (e.g., 'genome_data')
 * @returns {{expanded: Array<Object>, groups: string[]}} Expanded data and group labels
 */
function expandJsonColumn(data, xKey, jsonKey) {
    // First pass: parse JSON and collect all group keys
    const groupSet = new Set();
    const parsed = data.map(row => {
        const x = toNumber(row[xKey]);
        const jsonStr = row[jsonKey];
        let map = {};
        if (jsonStr && typeof jsonStr === 'string') {
            try {
                map = JSON.parse(jsonStr);
            } catch (e) {
                // Skip malformed JSON
            }
        }
        for (const key of Object.keys(map)) {
            groupSet.add(key);
        }
        return { x, map };
    });

    // Sort "other" to end, rest alphabetically
    const groups = [...groupSet].sort((a, b) => {
        if (a === 'other') return 1;
        if (b === 'other') return -1;
        return a.localeCompare(b);
    });

    // Second pass: build wide-format rows with all groups present
    const expanded = parsed.map(({ x, map }) => {
        const row = { [xKey]: x };
        for (const group of groups) {
            row[group] = map[group] || 0;
        }
        return row;
    });

    return { expanded, groups };
}

    /**
     * Renders a stacked area chart.
     *
     * Supports two modes:
     * 1. Wide format: config.y is array of column names (existing behavior)
     * 2. Long format with groupBy: config.groupBy specifies grouping column
     *
     * @param {HTMLCanvasElement} canvas - Canvas element
     * @param {Array<Object>} data - Data rows (array of objects)
     * @param {Object} config - Visualization config with x, y, and optional groupBy
     * @returns {Chart} Chart.js instance
     */
export function render(canvas, data, config) {
        const ctx = canvas.getContext('2d');

        const xKey = config.x || 'tick';
        const isPercentage = config.yAxisMode === 'percent';

        let labels, yKeys, chartData;

        // Check for jsonColumn mode (JSON-encoded map per row)
        if (config.jsonColumn) {
            const { expanded, groups } = expandJsonColumn(data, xKey, config.jsonColumn);
            chartData = expanded;
            yKeys = groups;
            labels = expanded.map(row => toNumber(row[xKey]));
        } else if (config.groupBy) {
            // Long-format data with groupBy pivoting
            const valueKey = config.y || 'count';
            const { pivoted, groups } = pivotData(data, xKey, config.groupBy, valueKey);
            chartData = pivoted;
            yKeys = groups;
            labels = pivoted.map(row => toNumber(row[xKey]));
        } else {
            // Traditional wide-format mode
            yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
            chartData = data;
            labels = data.map(row => toNumber(row[xKey]));
        }

        // percentBase: columns for percentage denominator (may include non-plotted columns like empty_cells)
        const percentBaseKeys = isPercentage ? (config.percentBase || yKeys) : yKeys;
        const hasExternalBase = isPercentage && config.percentBase && config.percentBase.length > yKeys.length;
        const datasets = yKeys.map((key, index) => {
            const values = chartData.map(row => {
                const val = toNumber(row[key]) || 0;
                if (isPercentage) {
                    const sum = percentBaseKeys.reduce((acc, k) => acc + (toNumber(row[k]) || 0), 0);
                    return sum === 0 ? 0 : (val / sum) * 100;
                }
                return val;
            });
            return {
                label: config.groupBy ? key : formatLabel(key), // Use raw label for groupBy mode
                data: values,
                borderColor: getColor(index),
                backgroundColor: getColor(index) + '80',
                borderWidth: 1,
                fill: index === 0 ? 'origin' : '-1',
                tension: 0.2,
                pointRadius: 0,
                pointHoverRadius: 5
            };
        });

        // Y-axis format hint from plugin manifest: "integer" or "decimal"
        const yFormat = config.yFormat || null;

        const chartConfig = {
            type: 'line',
            data: {
                labels: labels,
                datasets: datasets
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: {
                    mode: 'index',
                    intersect: false
                },
                plugins: {
                    legend: {
                        position: 'top',
                        labels: {
                            color: '#e0e0e0',
                            font: { family: "'Courier New', monospace", size: 11 },
                            usePointStyle: true,
                            pointStyle: 'rect'
                        },
                        // Custom click handler to adjust Y-axis max
                        onClick: isPercentage ? createLegendClickHandler() : undefined
                    },
                    tooltip: {
                        backgroundColor: '#191923',
                        titleColor: '#e0e0e0',
                        bodyColor: '#aaa',
                        borderColor: '#333',
                        borderWidth: 1,
                        padding: 12,
                        callbacks: {
                            title: items => `Tick ${items[0].label}`,
                            label: context => {
                                let label = context.dataset.label || '';
                                if (label) {
                                    label += ': ';
                                }
                                if (context.parsed.y !== null) {
                                    if (isPercentage) {
                                        label += context.parsed.y.toFixed(1) + '%';
                                    } else if (yFormat === 'integer') {
                                        label += Math.round(context.parsed.y);
                                    } else {
                                        label += context.parsed.y.toFixed(2);
                                    }
                                }
                                return label;
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        title: { display: false },
                        ticks: {
                            color: '#888',
                            maxTicksLimit: 10,
                            callback: function(value) {
                                const label = this.getLabelForValue(value);
                                return formatTickValue(label);
                            }
                        },
                        grid: { color: '#333', drawBorder: false }
                    },
                    y: {
                        stacked: true,
                        min: 0, // Always start at 0
                        max: (isPercentage && !hasExternalBase) ? 100 : undefined,
                        title: { display: false },
                        ticks: {
                            color: '#888',
                            ...(isPercentage ? {
                                callback: function(value) {
                                    const max = this.max || 100;
                                    const decimals = max < 10 ? 1 : 0;
                                    return value.toFixed(decimals) + '%';
                                }
                            } : yFormat === 'integer' ? {
                                callback: function(value) {
                                    return Number.isInteger(value) ? value : null;
                                }
                            } : {})
                        },
                        grid: { color: '#333', drawBorder: false }
                    }
                },
                animation: {
                    duration: 500
                }
            }
        };
        
        const chart = new Chart(ctx, chartConfig);
        
        // Store metadata
        chart._isPercentage = isPercentage;
        chart._hasExternalBase = hasExternalBase;
        chart._totalDatasets = datasets.length;
        
        return chart;
    }
    
export function update(chart, data, config) {
        const xKey = config.x || 'tick';
        const isPercentage = config.yAxisMode === 'percent';

        let yKeys, chartData;

        // Check for jsonColumn mode
        if (config.jsonColumn) {
            const { expanded, groups } = expandJsonColumn(data, xKey, config.jsonColumn);
            chartData = expanded;
            yKeys = groups;
            chart.data.labels = expanded.map(row => toNumber(row[xKey]));

            // Rebuild datasets if groups changed
            if (chart.data.datasets.length !== groups.length) {
                chart.data.datasets = groups.map((key, index) => ({
                    label: key,
                    data: [],
                    borderColor: getColor(index),
                    backgroundColor: getColor(index) + '80',
                    borderWidth: 1,
                    fill: index === 0 ? 'origin' : '-1',
                    tension: 0.2,
                    pointRadius: 0,
                    pointHoverRadius: 5
                }));
                chart._totalDatasets = groups.length;
            }
        } else if (config.groupBy) {
            // Long-format data with groupBy pivoting
            const valueKey = config.y || 'count';
            const { pivoted, groups } = pivotData(data, xKey, config.groupBy, valueKey);
            chartData = pivoted;
            yKeys = groups;
            chart.data.labels = pivoted.map(row => toNumber(row[xKey]));

            // Rebuild datasets if groups changed
            if (chart.data.datasets.length !== groups.length) {
                chart.data.datasets = groups.map((key, index) => ({
                    label: key,
                    data: [],
                    borderColor: getColor(index),
                    backgroundColor: getColor(index) + '80',
                    borderWidth: 1,
                    fill: index === 0 ? 'origin' : '-1',
                    tension: 0.2,
                    pointRadius: 0,
                    pointHoverRadius: 5
                }));
                chart._totalDatasets = groups.length;
            }
        } else {
            yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
            chartData = data;
            chart.data.labels = data.map(row => toNumber(row[xKey]));
        }

        const percentBaseKeys = isPercentage ? (config.percentBase || yKeys) : yKeys;
        yKeys.forEach((key, index) => {
            if (chart.data.datasets[index]) {
                chart.data.datasets[index].data = chartData.map(row => {
                    const val = toNumber(row[key]) || 0;
                    if (isPercentage) {
                        const sum = percentBaseKeys.reduce((acc, k) => acc + (toNumber(row[k]) || 0), 0);
                        return sum === 0 ? 0 : (val / sum) * 100;
                    }
                    return val;
                });
            }
        });

        chart.update('none');
    }
    
export function destroy(chart) {
        if (chart) {
            chart.destroy();
        }
    }

// Register with ChartRegistry
ChartRegistry.register('stacked-area-chart', { render, update, destroy });
