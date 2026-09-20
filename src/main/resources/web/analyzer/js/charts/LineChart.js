import * as ChartRegistry from './ChartRegistry.js';
import { formatTickValue, axisTicks, tooltipTitle, tooltipValue } from './ChartUtils.js';
import * as GenomeDepthSeries from './GenomeDepthSeries.js';

/**
 * Line Chart Implementation
 * 
 * Renders time-series data as line charts using Chart.js.
 * Supports dual Y-axes and automatic color assignment.
 *
 * A metric may add series the chart derives from its companion tables rather than reads from its
 * own rows: the config names a derivation under {@code derivedY2}, and its series are drawn on the
 * secondary axis. The derivation owns everything about those series; this chart only draws them.
 *
 * A metric may also choose what its series look like: {@code colors} gives a series its colour,
 * {@code yLabel} titles the left axis, {@code yMin} fixes its lower end, and {@code reference}
 * names the one series the others are measured against, which is drawn as the scale rather than as
 * a measurement.
 *
 * @module LineChart
 */

    // Evochora color palette
    const COLORS = [
        '#4a9eff', // accent blue
        '#a0e0a0', // accent green
        '#ffb366', // accent orange
        '#dda0dd', // plum
        '#87ceeb', // sky blue
        '#ffd700', // gold
        '#ff6b6b', // coral
        '#98d8c8', // mint
    ];
    
    // The series the others are held against carries no measurement of its own: grey and dashed,
    // it reads as the scale it is rather than as one more curve
    const REFERENCE_COLOR = '#9aa0a6';
    const REFERENCE_DASH = [6, 4];

    /**
     * Gets color for a series by index.
     */
    function getColor(index) {
        return COLORS[index % COLORS.length];
    }

    /**
     * The name the reference series carries in the legend and the tooltip.
     *
     * @param {Object} config - Visualization config
     * @returns {string|null} The label, or null where the config names no reference series
     */
    function referenceLabelOf(config) {
        if (!config.reference) {
            return null;
        }
        return config.referenceLabel || formatLabel(config.reference);
    }

    /**
     * Converts BigInt values to Numbers for Chart.js compatibility.
     * DuckDB returns BIGINT columns as JavaScript BigInt, but Chart.js requires Number.
     */
    function toNumber(value) {
        if (typeof value === 'bigint') {
            return Number(value);
        }
        return value;
    }

/** Derivations a metric can name under {@code derivedY2}, by name. */
const DERIVATIONS = {
    'genome-depth': GenomeDepthSeries
};

/**
 * Formats a column key as a human-readable label.
 * Converts snake_case to Title Case.
 */
function formatLabel(key) {
    return key
        .split('_')
        .map(word => word.charAt(0).toUpperCase() + word.slice(1))
        .join(' ');
}

    /**
     * Renders a line chart.
     * 
     * @param {HTMLCanvasElement} canvas - Canvas element
     * @param {Array<Object>} data - Data rows (array of objects)
     * @param {Object} config - Visualization config with x, y, y2 fields
     * @param {Object} [context] - Render context; its companion rows feed derived series
     * @returns {Chart} Chart.js instance
     */
export function render(canvas, data, config, context = {}) {
        const ctx = canvas.getContext('2d');
        
        // Extract config
        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        const y2Keys = Array.isArray(config.y2) ? config.y2 : (config.y2 ? [config.y2] : []);
        
        // Prepare labels (x-axis values) - convert BigInt to Number
        const labels = data.map(row => toNumber(row[xKey]));
        
        // Colours the metric chose for its series; a series it does not name keeps its palette one
        const seriesColors = config.colors || {};
        const referenceLabel = referenceLabelOf(config);

        // Prepare datasets
        const datasets = [];
        let colorIndex = 0;

        // Primary Y-axis datasets - convert BigInt to Number
        yKeys.forEach(key => {
            const values = data.map(row => toNumber(row[key]));
            const reference = key === config.reference;
            const color = reference ? REFERENCE_COLOR : (seriesColors[key] || getColor(colorIndex));
            datasets.push({
                label: reference ? referenceLabel : formatLabel(key),
                data: values,
                borderColor: color,
                backgroundColor: color + '20',
                borderWidth: 2,
                ...(reference ? { borderDash: REFERENCE_DASH } : {}),
                fill: false,
                tension: 0.1,
                pointRadius: reference ? 0 : (data.length > 100 ? 0 : 3),
                pointHoverRadius: 5,
                yAxisID: 'y'
            });
            colorIndex++;
        });

        // Secondary Y-axis datasets - convert BigInt to Number
        y2Keys.forEach(key => {
            const values = data.map(row => toNumber(row[key]));
            const color = seriesColors[key] || getColor(colorIndex);
            datasets.push({
                label: formatLabel(key),
                data: values,
                borderColor: color,
                backgroundColor: color + '20',
                borderWidth: 2,
                borderDash: [5, 5], // Dashed line for secondary axis
                fill: false,
                tension: 0.1,
                pointRadius: data.length > 100 ? 0 : 3,
                pointHoverRadius: 5,
                yAxisID: 'y2'
            });
            colorIndex++;
        });

        // Series derived from companion tables, drawn on the secondary axis like y2 columns
        const derivation = DERIVATIONS[config.derivedY2];
        const derived = derivation ? derivation.derive(labels, context.companion || null, config) : [];
        derived.forEach(series => {
            datasets.push({
                label: series.label,
                data: series.values,
                borderColor: getColor(colorIndex),
                backgroundColor: getColor(colorIndex) + '20',
                borderWidth: 2,
                borderDash: [5, 5],
                fill: false,
                tension: 0.1,
                spanGaps: true,
                pointRadius: data.length > 100 ? 0 : 3,
                pointHoverRadius: 5,
                yAxisID: 'y2'
            });
            colorIndex++;
        });
        const y2Titles = [...y2Keys.map(formatLabel), ...derived.map(series => series.label)];

        // Y-axis format hints from plugin manifest: "integer" or "decimal"
        const yFormat = config.yFormat || null;
        const y2Format = config.y2Format || null;

        // A metric that names its left axis says what the series have in common; without one the
        // axis lists them
        const yTitle = config.yLabel || yKeys.map(formatLabel).join(', ');

        // Chart configuration
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
                            font: {
                                family: "'Courier New', monospace",
                                size: 11
                            },
                            usePointStyle: true,
                            pointStyle: 'line'
                        }
                    },
                    tooltip: {
                        backgroundColor: '#191923',
                        titleColor: '#e0e0e0',
                        bodyColor: '#aaa',
                        borderColor: '#333',
                        borderWidth: 1,
                        padding: 12,
                        displayColors: true,
                        callbacks: {
                            title: tooltipTitle,
                            label: function(context) {
                                let label = context.dataset.label || '';
                                if (label) label += ': ';
                                if (context.parsed.y == null) return label;
                                const format = context.dataset.yAxisID === 'y2' ? y2Format : yFormat;
                                return label + tooltipValue(context.parsed.y, format);
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
                                const firstLabel = this.getLabelForValue(this.min);
                                const lastLabel = this.getLabelForValue(this.max);
                                const range = Number(lastLabel) - Number(firstLabel);
                                return formatTickValue(label, range);
                            }
                        },
                        grid: {
                            color: '#333',
                            drawBorder: false
                        }
                    },
                    y: {
                        type: 'linear',
                        display: true,
                        position: 'left',
                        title: {
                            display: yTitle.length > 0,
                            text: yTitle,
                            color: '#888'
                        },
                        ...(Number.isFinite(config.yMin) ? { min: config.yMin } : {}),
                        ticks: {
                            color: '#888',
                            ...axisTicks(yFormat)
                        },
                        grid: {
                            color: '#333',
                            drawBorder: false
                        }
                    },
                    y2: {
                        type: 'linear',
                        display: y2Titles.length > 0,
                        position: 'right',
                        title: {
                            display: y2Titles.length > 0,
                            text: y2Titles.join(', '),
                            color: '#888'
                        },
                        ticks: {
                            color: '#888',
                            ...axisTicks(y2Format)
                        },
                        grid: {
                            drawOnChartArea: false
                        }
                    }
                },
                animation: {
                    duration: 500
                }
            }
        };
        
        return new Chart(ctx, chartConfig);
    }
    
    /**
     * Updates an existing chart with new data.
     * 
     * @param {Chart} chart - Existing Chart.js instance
     * @param {Array<Object>} data - New data rows
     * @param {Object} config - Visualization config
     */
export function update(chart, data, config) {
        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        const y2Keys = Array.isArray(config.y2) ? config.y2 : (config.y2 ? [config.y2] : []);
        
        // Update labels - convert BigInt to Number
        chart.data.labels = data.map(row => toNumber(row[xKey]));
        
        // Update datasets - convert BigInt to Number
        let datasetIndex = 0;
        
        yKeys.forEach(key => {
            if (chart.data.datasets[datasetIndex]) {
                chart.data.datasets[datasetIndex].data = data.map(row => toNumber(row[key]));
            }
            datasetIndex++;
        });
        
        y2Keys.forEach(key => {
            if (chart.data.datasets[datasetIndex]) {
                chart.data.datasets[datasetIndex].data = data.map(row => toNumber(row[key]));
            }
            datasetIndex++;
        });
        
        // Adjust point radius based on data size; the reference series is drawn without points
        // however few there are
        const pointRadius = data.length > 100 ? 0 : 3;
        const referenceLabel = referenceLabelOf(config);
        chart.data.datasets.forEach(ds => {
            if (ds.label !== referenceLabel) {
                ds.pointRadius = pointRadius;
            }
        });
        
        chart.update('none'); // No animation for updates
    }
    
    /**
     * Destroys a chart instance.
     * 
     * @param {Chart} chart - Chart.js instance
     */
export function destroy(chart) {
        if (chart) {
            chart.destroy();
        }
    }

// Register with ChartRegistry
ChartRegistry.register('line-chart', { render, update, destroy });
